package com.example.demo.model;

import lombok.Getter;

@Getter
public enum SOURCE {
    MICROPHONE("Microphone (Logi C270 HD WebCam)", 400),
    STEREO_MIX("Stereo Mix (Realtek(R) Audio)", 100);

    private final String dshowName;
    private final int threshold;

    SOURCE(String dshowName, int threshold) {
        this.dshowName = dshowName;
        this.threshold = threshold;
    }

}
