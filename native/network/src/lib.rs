use std::slice;

const ABI_VERSION: u32 = 1;
const MAX_SAMPLES: usize = 4096;
const SIMD_ABI_VERSION: u32 = 1;
const SIMD_AVX2: u32 = 1 << 0;
const SIMD_FMA: u32 = 1 << 1;
const MAX_SIMD_ELEMENTS: usize = 262_144;
const MAX_SIMD_ITERATIONS: usize = 4096;
const MAX_SIMD_OPERATIONS: usize = 100_000_000;

#[no_mangle]
pub extern "C" fn fiximizer_network_backend_abi() -> u32 {
    ABI_VERSION
}

/// Reports independently detected CPU capabilities. Runtime feature detection
/// also checks that the operating system enables the required extended register state.
#[no_mangle]
pub extern "C" fn fiximizer_simd_backend_abi() -> u32 {
    SIMD_ABI_VERSION
}

#[no_mangle]
pub extern "C" fn fiximizer_simd_feature_flags() -> u32 {
    let mut flags = 0;

    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        if std::arch::is_x86_feature_detected!("avx2") {
            flags |= SIMD_AVX2;
        }
        if std::arch::is_x86_feature_detected!("fma") {
            flags |= SIMD_FMA;
        }
    }

    flags
}

/// Computes the same p50/p95/p99 summary as the Java fallback.
///
/// # Safety
/// samples must point to length readable i64 values and output must point to
/// at least three writable i64 values. Both pointers must remain valid.
#[no_mangle]
pub unsafe extern "C" fn fiximizer_network_summarize(
    samples: *const i64,
    length: i32,
    output: *mut i64,
) -> i32 {
    if samples.is_null() || output.is_null() || length <= 0 {
        return -1;
    }
    let count = length as usize;
    if count > MAX_SAMPLES {
        return -2;
    }

    let input = slice::from_raw_parts(samples, count);
    let mut sorted = input.to_vec();
    sorted.sort_unstable();

    output.write(percentile(&sorted, 50));
    output.add(1).write(percentile(&sorted, 95));
    output.add(2).write(percentile(&sorted, 99));
    0
}

fn percentile(sorted: &[i64], percentile: usize) -> i64 {
    sorted[((sorted.len() - 1) * percentile) / 100]
}

/// Portable scalar reference implementation. It deliberately uses separate
/// multiply and add operations so the SIMD FMA path can be checked against it.
#[no_mangle]
pub unsafe extern "C" fn fiximizer_simd_compute_scalar(
    initial: *const f32,
    multiplier: *const f32,
    addend: *const f32,
    output: *mut f32,
    length: i32,
    iterations: i32,
) -> i32 {
    let (initial, multiplier, addend, output, iterations) =
        match unsafe { checked_simd_buffers(initial, multiplier, addend, output, length, iterations) } {
            Ok(buffers) => buffers,
            Err(code) => return code,
        };

    compute_scalar(initial, multiplier, addend, output, iterations);
    0
}

/// Runs the best implementation supported by this CPU and operating system:
/// AVX2+FMA, AVX2 multiply/add, or the portable scalar implementation.
///
/// Return values: 0 = scalar, 1 = AVX2, 2 = AVX2+FMA; negative = invalid input.
///
/// # Safety
/// All input pointers must reference length readable f32 values and output
/// must reference length writable f32 values. The regions must not overlap.
#[no_mangle]
pub unsafe extern "C" fn fiximizer_simd_compute_auto(
    initial: *const f32,
    multiplier: *const f32,
    addend: *const f32,
    output: *mut f32,
    length: i32,
    iterations: i32,
) -> i32 {
    let (initial, multiplier, addend, output, iterations) =
        match unsafe { checked_simd_buffers(initial, multiplier, addend, output, length, iterations) } {
            Ok(buffers) => buffers,
            Err(code) => return code,
        };

    #[cfg(target_arch = "x86_64")]
    {
        if std::arch::is_x86_feature_detected!("avx2")
            && std::arch::is_x86_feature_detected!("fma")
        {
            unsafe {
                compute_avx2_fma(initial, multiplier, addend, output, iterations);
            }
            return 2;
        }

        if std::arch::is_x86_feature_detected!("avx2") {
            unsafe {
                compute_avx2(initial, multiplier, addend, output, iterations);
            }
            return 1;
        }
    }

    compute_scalar(initial, multiplier, addend, output, iterations);
    0
}

/// # Safety
/// The caller must guarantee valid, non-overlapping buffers and a positive,
/// bounded iteration count.
unsafe fn checked_simd_buffers<'a>(
    initial: *const f32,
    multiplier: *const f32,
    addend: *const f32,
    output: *mut f32,
    length: i32,
    iterations: i32,
) -> Result<(&'a [f32], &'a [f32], &'a [f32], &'a mut [f32], usize), i32> {
    if initial.is_null()
        || multiplier.is_null()
        || addend.is_null()
        || output.is_null()
        || length <= 0
        || iterations <= 0
    {
        return Err(-1);
    }

    let count = length as usize;
    let iteration_count = iterations as usize;
    if count > MAX_SIMD_ELEMENTS
        || iteration_count > MAX_SIMD_ITERATIONS
        || count.saturating_mul(iteration_count) > MAX_SIMD_OPERATIONS
    {
        return Err(-2);
    }

    Ok((
        slice::from_raw_parts(initial, count),
        slice::from_raw_parts(multiplier, count),
        slice::from_raw_parts(addend, count),
        slice::from_raw_parts_mut(output, count),
        iteration_count,
    ))
}

fn compute_scalar(
    initial: &[f32],
    multiplier: &[f32],
    addend: &[f32],
    output: &mut [f32],
    iterations: usize,
) {
    for index in 0..initial.len() {
        let mut value = initial[index];
        for _ in 0..iterations {
            value = value * multiplier[index] + addend[index];
        }
        output[index] = value;
    }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn compute_avx2(
    initial: &[f32],
    multiplier: &[f32],
    addend: &[f32],
    output: &mut [f32],
    iterations: usize,
) {
    use std::arch::x86_64::{
        _mm256_add_ps, _mm256_loadu_ps, _mm256_mul_ps, _mm256_storeu_ps,
    };

    let vector_end = initial.len() / 8 * 8;
    let mut index = 0;
    while index < vector_end {
        let mut value = unsafe { _mm256_loadu_ps(initial.as_ptr().add(index)) };
        let factor = unsafe { _mm256_loadu_ps(multiplier.as_ptr().add(index)) };
        let offset = unsafe { _mm256_loadu_ps(addend.as_ptr().add(index)) };

        for _ in 0..iterations {
            value = _mm256_add_ps(_mm256_mul_ps(value, factor), offset);
        }

        unsafe {
            _mm256_storeu_ps(output.as_mut_ptr().add(index), value);
        }
        index += 8;
    }

    for index in vector_end..initial.len() {
        let mut value = initial[index];
        for _ in 0..iterations {
            value = value * multiplier[index] + addend[index];
        }
        output[index] = value;
    }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2,fma")]
unsafe fn compute_avx2_fma(
    initial: &[f32],
    multiplier: &[f32],
    addend: &[f32],
    output: &mut [f32],
    iterations: usize,
) {
    use std::arch::x86_64::{_mm256_fmadd_ps, _mm256_loadu_ps, _mm256_storeu_ps};

    let vector_end = initial.len() / 8 * 8;
    let mut index = 0;
    while index < vector_end {
        let mut value = unsafe { _mm256_loadu_ps(initial.as_ptr().add(index)) };
        let factor = unsafe { _mm256_loadu_ps(multiplier.as_ptr().add(index)) };
        let offset = unsafe { _mm256_loadu_ps(addend.as_ptr().add(index)) };

        for _ in 0..iterations {
            value = _mm256_fmadd_ps(value, factor, offset);
        }

        unsafe {
            _mm256_storeu_ps(output.as_mut_ptr().add(index), value);
        }
        index += 8;
    }

    for index in vector_end..initial.len() {
        let mut value = initial[index];
        for _ in 0..iterations {
            value = value * multiplier[index] + addend[index];
        }
        output[index] = value;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn computes_percentiles_without_mutating_the_input() {
        let input = [9_i64, 1, 5, 3, 7];
        let mut output = [0_i64; 3];
        let result = unsafe {
            fiximizer_network_summarize(input.as_ptr(), input.len() as i32, output.as_mut_ptr())
        };
        assert_eq!(result, 0);
        assert_eq!(output, [5, 7, 7]);
        assert_eq!(input, [9, 1, 5, 3, 7]);
    }

    #[test]
    fn rejects_invalid_network_lengths() {
        let mut output = [0_i64; 3];
        let result = unsafe {
            fiximizer_network_summarize(
                [1_i64].as_ptr(),
                (MAX_SAMPLES + 1) as i32,
                output.as_mut_ptr(),
            )
        };
        assert_eq!(result, -2);
    }

    #[test]
    fn runtime_feature_flags_only_contain_known_bits() {
        assert_eq!(fiximizer_simd_backend_abi(), SIMD_ABI_VERSION);
        assert_eq!(fiximizer_simd_feature_flags() & !(SIMD_AVX2 | SIMD_FMA), 0);
    }

    #[test]
    fn dispatched_simd_matches_scalar_with_a_non_vector_multiple_length() {
        // 37 elements exercises four AVX2 vectors plus the scalar tail.
        let length = 37;
        let iterations = 127;
        let initial: Vec<f32> = (0..length).map(|i| 0.25 + i as f32 * 0.003).collect();
        let multiplier: Vec<f32> = (0..length)
            .map(|i| 0.9998 + (i % 17) as f32 * 0.00001)
            .collect();
        let addend: Vec<f32> = (0..length)
            .map(|i| 0.0001 + (i % 11) as f32 * 0.00001)
            .collect();
        let mut scalar = vec![0.0_f32; length];
        let mut dispatched = vec![0.0_f32; length];

        let scalar_status = unsafe {
            fiximizer_simd_compute_scalar(
                initial.as_ptr(),
                multiplier.as_ptr(),
                addend.as_ptr(),
                scalar.as_mut_ptr(),
                length as i32,
                iterations,
            )
        };
        let dispatched_path = unsafe {
            fiximizer_simd_compute_auto(
                initial.as_ptr(),
                multiplier.as_ptr(),
                addend.as_ptr(),
                dispatched.as_mut_ptr(),
                length as i32,
                iterations,
            )
        };

        assert_eq!(scalar_status, 0);
        assert!((0..=2).contains(&dispatched_path));

        for (expected, actual) in scalar.iter().zip(dispatched.iter()) {
            let tolerance = 1.0e-5_f32 * expected.abs().max(1.0);
            assert!(
                (expected - actual).abs() <= tolerance,
                "scalar={expected}, dispatched={actual}"
            );
        }
    }

    #[test]
    fn rejects_invalid_simd_buffers_and_work_sizes() {
        let input = [1.0_f32; 8];
        let mut output = [0.0_f32; 8];

        let null_result = unsafe {
            fiximizer_simd_compute_auto(
                std::ptr::null(),
                input.as_ptr(),
                input.as_ptr(),
                output.as_mut_ptr(),
                8,
                1,
            )
        };
        assert_eq!(null_result, -1);

        let oversized_result = unsafe {
            fiximizer_simd_compute_auto(
                input.as_ptr(),
                input.as_ptr(),
                input.as_ptr(),
                output.as_mut_ptr(),
                (MAX_SIMD_ELEMENTS + 1) as i32,
                1,
            )
        };
        assert_eq!(oversized_result, -2);
    }
}
