package com.example.demo.model;

import lombok.Getter;

@Getter
public enum SOURCE {
    MICROPHONE("Microphone (Logi C270 HD WebCam)"),
    STEREO_MIX("Stereo Mix (Realtek(R) Audio)");

    private final String dshowName;

    SOURCE(String dshowName) {
        this.dshowName = dshowName;
    }

}
