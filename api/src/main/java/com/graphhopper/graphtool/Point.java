package com.graphhopper.graphtool;

public class Point {
    public int nodeId;
    public int level;
    public double lat;
    public double lon;

    public Point(int nodeId, int level, double lat, double lon) {
        this.nodeId = nodeId;
        this.level = level;
        this.lat = lat;
        this.lon = lon;
    }
}
