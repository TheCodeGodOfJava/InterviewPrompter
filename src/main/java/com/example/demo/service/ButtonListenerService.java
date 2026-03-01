package com.example.demo.service;

import com.example.demo.service.ai.AiAnswerService;
import com.example.demo.service.ai.AiContextService;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseMotionListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ButtonListenerService implements NativeKeyListener, NativeMouseMotionListener {

    private final AiAnswerService aiAnswerService;
    private final AiContextService aiContextService;

    private final SimpMessagingTemplate messagingTemplate;

    private boolean isScrollModeActive = true;
    private Robot robot;
    private int lastY = -1;
    private int yAccumulator = 0;

    // Screen dimensions for the Treadmill effect
    private int screenWidth;
    private int screenHeight;

    private static final int DEADZONE = 2;
    private static final int SCROLL_THRESHOLD = 40;
    private static final int FIXED_SCROLL_AMOUNT = 60;

    // Safety check: If the cursor moves more than 200 pixels in 1 millisecond,
    // it was our Robot teleporting it, NOT the physical trackball.
    private static final int TELEPORT_JUMP_THRESHOLD = 200;

    @PostConstruct
    public void init() {
        try {
            robot = new Robot();
            // Get the primary monitor's dimensions
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            screenWidth = screenSize.width;
            screenHeight = screenSize.height;
        } catch (AWTException e) {
            log.error("Failed to initialize Java Robot.", e);
        }

        try {
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);

            log.info("Global Keyboard and Mouse Hook started successfully.");
        } catch (NativeHookException ex) {
            log.error("There was a problem registering the native hook.", ex);
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int modifiers = e.getModifiers();
        boolean hasCtrl = (modifiers & NativeKeyEvent.CTRL_L_MASK) != 0 || (modifiers & NativeKeyEvent.CTRL_R_MASK) != 0;
        boolean hasAlt = (modifiers & NativeKeyEvent.ALT_L_MASK) != 0 || (modifiers & NativeKeyEvent.ALT_R_MASK) != 0;
        boolean hasShift = (modifiers & NativeKeyEvent.SHIFT_L_MASK) != 0 || (modifiers & NativeKeyEvent.SHIFT_R_MASK) != 0;

        if (e.getKeyCode() == NativeKeyEvent.VC_F1 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 1-st button pressed!");
            aiContextService.clearAnsweredQuestions();
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F2 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 2-nd button pressed!");
            aiAnswerService.generateManualAnswer();
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F3 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 3-rd button pressed!");
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F4 && hasCtrl && hasAlt && hasShift) {
            log.info("Macro Detected! 4-th button pressed!");
            byte [] imageBytes = captureLeftMonitor();
            CompletableFuture.runAsync(() -> aiAnswerService.processScreenshot(imageBytes));
        }

        if (e.getKeyCode() == NativeKeyEvent.VC_F5 && hasCtrl && hasAlt && hasShift) {
            isScrollModeActive = !isScrollModeActive;
            log.info("Scroll mode toggled. Now active: {}", isScrollModeActive);

            lastY = -1;
            yAccumulator = 0;
        }
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        if (!isScrollModeActive) return;

        int currentY = e.getY();

        if (lastY == -1) {
            lastY = currentY;
            return;
        }

        int deltaY = currentY - lastY;

        // 1. Check if this is a massive jump caused by our Robot teleporting the cursor.
        // If it is, ignore the math, reset lastY, and return.
        if (Math.abs(deltaY) > TELEPORT_JUMP_THRESHOLD) {
            lastY = currentY;
            return;
        }

        // 2. Normal Scrolling Logic
        if (Math.abs(deltaY) > DEADZONE) {
            yAccumulator += deltaY;

            if (Math.abs(yAccumulator) >= SCROLL_THRESHOLD) {
                int direction = yAccumulator > 0 ? 1 : -1;
                messagingTemplate.convertAndSend("/topic/scroll", FIXED_SCROLL_AMOUNT * direction);
                yAccumulator = 0;
            }
        }

        // 3. The Treadmill: If the cursor gets within 100 pixels of the top or bottom of the screen,
        // instantly teleport it back to the exact center of the monitor.
        if (robot != null && (currentY <= 0 || currentY >= screenHeight - 1)) {

            // Teleport back to the center of the screen
            robot.mouseMove(screenWidth / 2, screenHeight / 2);
        } else {
            lastY = currentY;
        }
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent e) {
        nativeMouseMoved(e);
    }

    @PreDestroy
    public void cleanup() {
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            log.error("Failed to unregister native hook", e);
        }
    }


    /**
     * Determines which monitor is physically on the left and captures only that screen.
     *
     * @return PNG image as a byte array, or null if capture fails.
     */
    public byte[] captureLeftMonitor() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();

            if (screens.length == 0) {
                log.warn("No screens detected.");
                return null;
            }

            // Assume the first screen is the leftmost initially
            BufferedImage screenCapture = getBufferedImage(screens);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(screenCapture, "png", baos);

            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to capture the left monitor.", e);
            return null;
        }
    }

    private BufferedImage getBufferedImage(GraphicsDevice[] screens) {
        Rectangle leftScreenBounds = screens[0].getDefaultConfiguration().getBounds();

        // Loop through all connected monitors to find the true leftmost screen
        for (GraphicsDevice screen : screens) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            if (bounds.x < leftScreenBounds.x) {
                leftScreenBounds = bounds;
            }
        }
        return robot.createScreenCapture(leftScreenBounds);
    }
}