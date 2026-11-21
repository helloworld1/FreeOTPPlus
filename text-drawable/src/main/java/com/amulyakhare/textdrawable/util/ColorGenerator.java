package com.amulyakhare.textdrawable.util;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * @author amulya
 * @datetime 14 Oct 2014, 5:20 PM
 */
public class ColorGenerator {

    public static ColorGenerator DEFAULT;

    public static ColorGenerator MATERIAL;

    static {
        DEFAULT = create(Arrays.asList(
                0xfff16364,
                0xfff58559,
                0xfff9a43e,
                0xffe4c62e,
                0xff67bf74,
                0xff59a2be,
                0xff2093cd,
                0xffad62a7,
                0xff805781
        ));
        MATERIAL = create(Arrays.asList(
                0xffd32f2f,
                0xffc2185b,
                0xff9c27b0,
                0xff6e44c7,
                0xff4c5ec4,
                0xff1976d2,
                0xff0277bd,
                0xff00838f,
                0xff00796b,
                0xff2e7d32,
                0xff4a7f24,
                0xffa65e00,
                0xffcf3e12,
                0xffb8336a,
                0xff835d4f,
                0xff546e7a,
                0xff1b8270
        ));
    }

    private final List<Integer> mColors;
    private final Random mRandom;

    public static ColorGenerator create(List<Integer> colorList) {
        return new ColorGenerator(colorList);
    }

    private ColorGenerator(List<Integer> colorList) {
        mColors = colorList;
        mRandom = new Random(System.currentTimeMillis());
    }

    public int getRandomColor() {
        return mColors.get(mRandom.nextInt(mColors.size()));
    }

    public int getColor(Object key) {
        return mColors.get((key.hashCode() & 0x7fffffff) % mColors.size());
    }
}
