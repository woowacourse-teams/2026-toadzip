package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.domain.Wgs84Coordinate;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

@Component
public class UtmKCoordinateConverter {

    private static final String UTM_K_PARAMETERS = String.join(" ",
            "+proj=tmerc",
            "+lat_0=38",
            "+lon_0=127.5",
            "+k=0.9996",
            "+x_0=1000000",
            "+y_0=2000000",
            "+ellps=GRS80",
            "+units=m",
            "+no_defs"
    );

    private static final String WGS84_PARAMETERS = "+proj=longlat +datum=WGS84 +no_defs";

    private final CoordinateTransform transform;

    public UtmKCoordinateConverter() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem utmK = factory.createFromParameters("UTM-K", UTM_K_PARAMETERS);
        CoordinateReferenceSystem wgs84 = factory.createFromParameters("WGS84", WGS84_PARAMETERS);
        transform = new CoordinateTransformFactory().createTransform(utmK, wgs84);
    }

    public Wgs84Coordinate convert(UtmKCoordinate coordinate) {
        try {
            double x = finiteValue(coordinate.x());
            double y = finiteValue(coordinate.y());
            ProjCoordinate result = new ProjCoordinate();
            transform.transform(new ProjCoordinate(x, y), result);
            return new Wgs84Coordinate(decimal(result.y), decimal(result.x));
        }
        catch (RuntimeException exception) {
            throw new RoadAddressGeocodingException(
                    RoadAddressGeocodingFailureReason.COORDINATE_CONVERSION_ERROR,
                    "UTM-K 좌표를 WGS84 좌표로 변환하지 못했습니다.",
                    exception
            );
        }
    }

    private double finiteValue(BigDecimal value) {
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new IllegalArgumentException("UTM-K 좌표가 유한수가 아닙니다.");
        }
        return converted;
    }

    private BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("변환된 좌표가 유한수가 아닙니다.");
        }
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
