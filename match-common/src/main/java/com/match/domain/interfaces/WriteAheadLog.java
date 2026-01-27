package com.match.domain.interfaces;

import com.match.domain.OrderEvent;

import java.io.IOException;

public interface WriteAheadLog {
    void log(OrderEvent event);
    void close() throws IOException;
} 