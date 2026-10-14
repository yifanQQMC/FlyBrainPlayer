package com.flybrain.comm;

/** 脑区/动作常量，供对话与学习模块引用（避免循环依赖） */
public final class FlyBrainBrainConst {
    private FlyBrainBrainConst() {}

    /** 好奇心通道——奖励"去查资料"这个行为 */
    public static final int CURIOSITY = 11;

    // —— 自然环境通道 / 行为（与 FlyBrain 同步，供离线模拟器引用）——
    public static final int RAIN = 24;
    public static final int THUNDER = 25;
    public static final int TIRED = 26;
    public static final int HOME = 27;
    public static final int REST = 14;
    public static final int SHELTER = 15;
}