package com.calpano.ddot.psi;

import com.calpano.ddot.DdotLanguage;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class DdotElementType extends IElementType {
    public DdotElementType(@NotNull @NonNls String debugName) {
        super(debugName, DdotLanguage.INSTANCE);
    }
}
