package com.example.demo.service;

import com.example.demo.service.ai.AiAnswerService;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ButtonListenerService implements NativeKeyListener {

    private final AiAnswerService aiAnswerService;

    @PostConstruct
    public void init() {
        try {
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();

            GlobalScreen.addNativeKeyListener(this);

            log.info("Global Keyboard Hook started successfully.");
        } catch (NativeHookException ex) {
            log.error("There was a problem registering the native hook.", ex);
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        // 1. Get the currently pressed modifier keys
        int modifiers = e.getModifiers();
        boolean hasCtrl = (modifiers & NativeKeyEvent.CTRL_L_MASK) != 0 || (modifiers & NativeKeyEvent.CTRL_R_MASK) != 0;
        boolean hasAlt = (modifiers & NativeKeyEvent.ALT_L_MASK) != 0 || (modifiers & NativeKeyEvent.ALT_R_MASK) != 0;
        boolean hasShift = (modifiers & NativeKeyEvent.SHIFT_L_MASK) != 0 || (modifiers & NativeKeyEvent.SHIFT_R_MASK) != 0;

        // 2. Check if it matches exactly: Ctrl + Alt + Shift + F12
        if (e.getKeyCode() == NativeKeyEvent.VC_F1 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 1-st button pressed!");
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F2 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 2-nd button pressed!");
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F3 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 3-rd button pressed!");
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F4 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 4-th button pressed!");
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        // Not needed for this use case
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not needed for this use case
    }

    @PreDestroy
    public void cleanup() {
        try {
            GlobalScreen.unregisterNativeHook();
            log.info("Global Keyboard Hook stopped.");
        } catch (NativeHookException e) {
            log.error("Failed to unregister native hook", e);
        }
    }
}