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

    /** @param model a DIRECT ByteBuffer (native byte order) holding the .tflite file */
    public TfliteModel(ByteBuffer model) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(1);
        interpreter = new Interpreter(model, options);
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
