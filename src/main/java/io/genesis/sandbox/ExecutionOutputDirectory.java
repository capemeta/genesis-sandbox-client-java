package io.genesis.sandbox;

final class ExecutionOutputDirectory {
    private ExecutionOutputDirectory() {}

    static String validate(String value) {
        // null省略；显式空串、跨区、嵌套和宿主路径均不能自动规范化。
        if (value != null && !value.matches("output/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("outputDirectory must be output/<safe invocation id>");
        }
        return value;
    }
}
