package org.dromara.image.domain;

import java.util.List;

public final class ImageAspectRatio {

    private static final double MAX_RELATIVE_ERROR = 0.04D;
    private static final List<CommonRatio> COMMON_RATIOS = List.of(
        new CommonRatio("1:1", 1, 1),
        new CommonRatio("1:2", 1, 2),
        new CommonRatio("2:1", 2, 1),
        new CommonRatio("2:3", 2, 3),
        new CommonRatio("3:2", 3, 2),
        new CommonRatio("3:4", 3, 4),
        new CommonRatio("4:3", 4, 3),
        new CommonRatio("4:5", 4, 5),
        new CommonRatio("5:4", 5, 4),
        new CommonRatio("9:16", 9, 16),
        new CommonRatio("16:9", 16, 9)
    );

    private ImageAspectRatio() {
    }

    public static String format(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图片宽高必须大于0");
        }
        double actual = (double) width / height;
        CommonRatio closest = null;
        double closestError = Double.MAX_VALUE;
        for (CommonRatio ratio : COMMON_RATIOS) {
            double target = (double) ratio.width() / ratio.height();
            double error = Math.abs(actual - target) / target;
            if (error < closestError) {
                closest = ratio;
                closestError = error;
            }
        }
        return closest != null && closestError <= MAX_RELATIVE_ERROR ? closest.label() : "其他";
    }

    public static List<String> supportedValues() {
        return COMMON_RATIOS.stream().map(CommonRatio::label).toList();
    }

    private record CommonRatio(String label, int width, int height) {
    }
}
