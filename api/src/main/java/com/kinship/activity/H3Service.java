package com.kinship.activity;

import com.uber.h3core.H3Core;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class H3Service {

    public static final int RESOLUTION = 7;
    private final H3Core h3;

    public H3Service() {
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialise H3", e);
        }
    }

    public String cell(double lat, double lng) {
        return h3.h3ToString(h3.latLngToCell(lat, lng, RESOLUTION));
    }

    public java.util.List<String> kRing(double lat, double lng, int k) {
        long cell = h3.latLngToCell(lat, lng, RESOLUTION);
        return h3.gridDisk(cell, k).stream().map(h3::h3ToString).toList();
    }
}
