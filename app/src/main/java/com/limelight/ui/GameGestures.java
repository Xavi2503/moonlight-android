package com.limelight.ui;

import com.limelight.binding.input.GameInputDevice;

public interface GameGestures {
    void toggleKeyboard();

    default void toggleControllerKeyboard() {
        toggleKeyboard();
    }

    default void setPushToTalkPressed(boolean pressed){};

    default void showGameMenu(GameInputDevice device){};
}
