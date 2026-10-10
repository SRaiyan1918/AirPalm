package com.airpalm.app;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** One TensorFlow Lite model behind the small interface used by {@link OpenWakeWord}. */
public class TfliteModel implements OpenWakeWord.Model {
    private final Interpreter interpreter;
    private int[] currentShape;
    private ByteBuffer in, out;

    /**
     * @param model a DIRECT ByteBuffer (native byte order) holding the .tflite file
     * @param tryXnnpack true = use TensorFlow Lite's default fast CPU delegate (XNNPACK) if the model allows it.
     *                   Must be false for the melspectrogram model: its input length is not fixed, and the
     *                   delegate would try to prepare it with a 1-sample input and fail.
     *                   If the fast path fails for any model, the plain CPU path is used instead.
     */
    public TfliteModel(ByteBuffer model, boolean tryXnnpack) {
        Interpreter made = null;
        if (tryXnnpack) {
            try {
                Interpreter.Options fast = new Interpreter.Options();
                fast.setNumThreads(1);
                made = new Interpreter(model, fast);
            } catch (Exception e) {
                made = null;
            }
        }
        if (made == null) {
            model.rewind();
            Interpreter.Options plain = new Interpreter.Options();
            plain.setNumThreads(1);
            plain.setUseXNNPACK(false);
            made = new Interpreter(model, plain);
        }
        interpreter = made;
    }

    @Override
    public float[] run(float[] input, int[] shape) {
        if (!Arrays.equals(shape, currentShape)) {
            interpreter.resizeInput(0, shape);
            interpreter.allocateTensors();
            currentShape = shape.clone();
            in = ByteBuffer.allocateDirect(input.length * 4).order(ByteOrder.nativeOrder());
            out = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder());
            if (in.capacity() != interpreter.getInputTensor(0).numBytes()) {
                throw new IllegalStateException("model input is " + Arrays.toString(interpreter.getInputTensor(0).shape())
                        + " but " + Arrays.toString(shape) + " was sent");
            }
        }
        in.rewind();
        in.asFloatBuffer().put(input);
        in.rewind();
        out.rewind();
        interpreter.run(in, out);
        out.rewind();
        float[] result = new float[out.capacity() / 4];
        out.asFloatBuffer().get(result);
        return result;
    }

    /** e.g. "in [1, 76, 32, 1] -> out [1, 1, 1, 96]" for the status screen */
    public String describe() {
        return "in " + Arrays.toString(interpreter.getInputTensor(0).shape())
                + " out " + Arrays.toString(interpreter.getOutputTensor(0).shape());
    }

    public void close() {
        try {
            interpreter.close();
        } catch (Exception ignored) {
        }
    }
}
