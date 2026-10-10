package com.airpalm.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixes the stored input size of a .tflite file in memory (the file on disk is not touched).
 *
 * Why: the openWakeWord melspectrogram model is saved with an input of 1 sample but needs at least 512
 * (its first convolution has a 512 wide kernel). TensorFlow Lite prepares every layer the moment the model is
 * opened, using the stored size, and fails with "number of elements overflowed". Writing the real size
 * (1 x 1760) into the file bytes before opening it avoids that, with no flags or delegates involved.
 *
 * Pure Java (no Android classes) so it can be tested on a PC.
 */
public final class TfliteShapePatch {
    private TfliteShapePatch() {
    }

    /**
     * @param data       the whole .tflite file (is changed in place)
     * @param inputIndex which input of the first subgraph (0 = first)
     * @param newShape   must have the same number of dimensions as the stored shape
     * @return true if the shape was rewritten
     */
    public static boolean patchInputShape(byte[] data, int inputIndex, int[] newShape) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int model = b.getInt(0);

            int subgraphsVec = vector(b, model, 2);                    // Model.subgraphs
            if (subgraphsVec < 0 || b.getInt(subgraphsVec) < 1) return false;
            int subgraph = table(b, subgraphsVec + 4);                 // first subgraph

            int tensorsVec = vector(b, subgraph, 0);                   // SubGraph.tensors
            int inputsVec = vector(b, subgraph, 1);                    // SubGraph.inputs
            if (tensorsVec < 0 || inputsVec < 0 || inputIndex >= b.getInt(inputsVec)) return false;

            int tensorIndex = b.getInt(inputsVec + 4 + 4 * inputIndex);
            if (tensorIndex < 0 || tensorIndex >= b.getInt(tensorsVec)) return false;
            int tensor = table(b, tensorsVec + 4 + 4 * tensorIndex);

            int shapeVec = vector(b, tensor, 0);                       // Tensor.shape
            if (shapeVec < 0 || b.getInt(shapeVec) != newShape.length) return false;
            for (int i = 0; i < newShape.length; i++) b.putInt(shapeVec + 4 + 4 * i, newShape[i]);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** reads the stored input shape (for the status screen / tests); null if unreadable */
    public static int[] readInputShape(byte[] data, int inputIndex) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int model = b.getInt(0);
            int subgraphsVec = vector(b, model, 2);
            int subgraph = table(b, subgraphsVec + 4);
            int tensorsVec = vector(b, subgraph, 0);
            int inputsVec = vector(b, subgraph, 1);
            int tensor = table(b, tensorsVec + 4 + 4 * b.getInt(inputsVec + 4 + 4 * inputIndex));
            int shapeVec = vector(b, tensor, 0);
            int[] r = new int[b.getInt(shapeVec)];
            for (int i = 0; i < r.length; i++) r[i] = b.getInt(shapeVec + 4 + 4 * i);
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- minimal FlatBuffers reading ----

    /** position of a table that is referenced by an offset stored at pos */
    private static int table(ByteBuffer b, int pos) {
        return pos + b.getInt(pos);
    }

    /** position of the vector stored in field `slot` of `table` (points at the vector's length), or -1 */
    private static int vector(ByteBuffer b, int table, int slot) {
        int vtable = table - b.getInt(table);
        int vtableSize = b.getShort(vtable) & 0xFFFF;
        int entry = 4 + 2 * slot;
        if (entry >= vtableSize) return -1;
        int off = b.getShort(vtable + entry) & 0xFFFF;
        if (off == 0) return -1;
        int fieldPos = table + off;
        return fieldPos + b.getInt(fieldPos);
    }
}
