package dev.erezalkalay.jobhunter.cv.dto;

public record ActiveCvResponse(CvResponse cv) {

    public static final ActiveCvResponse NONE = new ActiveCvResponse(null);
}
