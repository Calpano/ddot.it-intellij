package com.calpano.ddot.psi;

import com.calpano.ddot.DdotLanguage;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class DdotTokenType extends IElementType {
    public DdotTokenType(@NotNull @NonNls String debugName) {
        super(debugName, DdotLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "DdotTokenType." + super.toString();
    }
}
