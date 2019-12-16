package net.gegy1000.terrarium.server.util;

public final class Interpolate {
    public static final Interpolate NEAREST = new Interpolate(new Kernel(1), (buffer, x) -> buffer[0]);
    public static final Interpolate LINEAR = new Interpolate(new Kernel(2), (buffer, x) -> {
        return buffer[0] + (buffer[1] - buffer[0]) * x;
    });
    public static final Interpolate COSINE = new Interpolate(new Kernel(2), (buffer, x) -> {
        return LINEAR.evaluate(buffer, (1.0 - Math.cos(x * Math.PI)) / 2.0);
    });
    public static final Interpolate CUBIC = new Interpolate(new Kernel(4).offset(-1), (b, x) -> {
        return b[1] + 0.5 * x * (b[2] - b[0] + x * (2.0 * b[0] - 5.0 * b[1] + 4.0 * b[2] - b[3] + x * (3.0 * (b[1] - b[2]) + b[3] - b[0])));
    });

    private final Kernel kernel;
    private final Function function;

    private Interpolate(Kernel kernel, Function function) {
        this.kernel = kernel;
        this.function = function;
    }

    public Kernel getKernel() {
        return this.kernel;
    }

    public double evaluate(double[] kernel, double x) {
        if (!this.kernel.fits(kernel)) throw new IllegalStateException("wrong kernel size");
        return this.function.evaluate(kernel, x);
    }

    public double evaluate(double[][] buffer, double x, double y) {
        double[] verticalSampleBuffer = this.kernel.getBuffer();
        for (int kernelX = 0; kernelX < this.kernel.width; kernelX++) {
            verticalSampleBuffer[kernelX] = this.evaluate(buffer[kernelX], y);
        }
        return this.evaluate(verticalSampleBuffer, x);
    }

    public static class Kernel {
        private final int width;
        private int offset;

        private final ThreadLocal<double[]> buffer;

        public Kernel(int width) {
            this.width = width;
            this.buffer = ThreadLocal.withInitial(() -> new double[width]);
        }

        public Kernel offset(int offset) {
            this.offset = offset;
            return this;
        }

        public boolean fits(double[] buffer) {
            return this.width == buffer.length;
        }

        public int getWidth() {
            return this.width;
        }

        public int getOffset() {
            return this.offset;
        }

        public double[] getBuffer() {
            return this.buffer.get();
        }
    }

    public interface Function {
        double evaluate(double[] buffer, double x);
    }
}