package com.calpano.ddot.index;

import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.IOUtil;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code FileBasedIndex} value externalizer for a name → occurrence-count map.
 *
 * <p>Counts rather than a plain name set, because the Autocomplete
 * Specification ranks candidates by descending corpus frequency (key 6). A set
 * cannot answer that: it records that a name exists, not how often it was
 * chosen, so a one-off typo would rank level with the project's most-used
 * relation.
 */
public final class StringCountExternalizer implements DataExternalizer<Map<String, Integer>> {

    public static final StringCountExternalizer INSTANCE = new StringCountExternalizer();

    private StringCountExternalizer() {}

    @Override
    public void save(@NotNull DataOutput out, Map<String, Integer> value) throws IOException {
        DataInputOutputUtil.writeINT(out, value.size());
        for (Map.Entry<String, Integer> e : value.entrySet()) {
            IOUtil.writeUTF(out, e.getKey());
            DataInputOutputUtil.writeINT(out, e.getValue());
        }
    }

    @Override
    public Map<String, Integer> read(@NotNull DataInput in) throws IOException {
        int n = DataInputOutputUtil.readINT(in);
        Map<String, Integer> map = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            String name = IOUtil.readUTF(in);
            map.put(name, DataInputOutputUtil.readINT(in));
        }
        return map;
    }
}
