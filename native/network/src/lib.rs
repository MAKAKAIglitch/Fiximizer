use std::slice;

const ABI_VERSION: u32 = 1;
const MAX_SAMPLES: usize = 4096;

#[no_mangle]
pub extern "C" fn fiximizer_network_backend_abi() -> u32 {
    ABI_VERSION
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
    fn rejects_invalid_lengths() {
        let mut output = [0_i64; 3];
        let result = unsafe {
            fiximizer_network_summarize([1_i64].as_ptr(), (MAX_SAMPLES + 1) as i32, output.as_mut_ptr())
        };
        assert_eq!(result, -2);
    }
}
