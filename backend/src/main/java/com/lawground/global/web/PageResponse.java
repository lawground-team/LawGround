package com.lawground.global.web;

import java.util.List;

public record PageResponse<T>(List<T> items, String nextCursor) {}
