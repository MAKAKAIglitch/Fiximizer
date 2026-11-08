package com.makakaiglitch.fiximizer.gpu;

public record ComputeDecision(Target target, String reason) {
    public enum Target {
        CPU,
        GPU
    }
}
