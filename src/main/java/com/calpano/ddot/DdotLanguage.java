package com.calpano.ddot;

import com.intellij.lang.Language;

public final class DdotLanguage extends Language {
    public static final DdotLanguage INSTANCE = new DdotLanguage();

    private DdotLanguage() {
        super("ddot.it");
    }
}
