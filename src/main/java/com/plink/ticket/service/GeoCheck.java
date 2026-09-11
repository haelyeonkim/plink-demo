package com.plink.ticket.service;

import com.plink.ticket.model.EventSession;

/**
 * Coarse location binding for presentation grants. It exists to kill the "open the QR at
 * home and send it over" path, not to prove anyone's position: indoor GPS is far too
 * noisy for that, which is why a poor fix counts as unknown rather than as a failure and
 * why the default mode records instead of refusing.
 */
public final class GeoCheck {
    /** Beyond this accuracy the reading says nothing useful about a 300 m radius. */
    static final double USELESS_ACCURACY_METERS = 500;

    public static class Result {
        public final Boolean ok;
        public final Double distanceMeters;
        public final String note;
        Result(Boolean ok, Double distanceMeters, String note) {
            this.ok = ok;
            this.distanceMeters = distanceMeters;
            this.note = note;
        }
    }

    private GeoCheck() {}

    public static Result evaluate(EventSession session, Double lat, Double lon, Double accuracy) {
        if (session.geoMode == null || "OFF".equals(session.geoMode)
                || session.venueLat == null || session.venueLon == null) {
            return new Result(null, null, null);
        }
        if (lat == null || lon == null) {
            return new Result(null, null, "위치 정보 없음");
        }
        if (accuracy != null && accuracy > USELESS_ACCURACY_METERS) {
            return new Result(null, null, "위치 정확도 부족");
        }
        double distance = haversineMeters(session.venueLat, session.venueLon, lat, lon);
        boolean within = distance <= session.geoRadiusMeters;
        return new Result(within, distance, within ? null : "공연장 반경 밖");
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
