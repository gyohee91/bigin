package com.ghyinc.finance.domain.kcbcredit.file;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class KcbFilePoller {
    private final KcbFileProperties properties;

    public List<Path> pollNewFiles() throws IOException {
        try(Stream<Path> stream = Files.list(Path.of(properties.getLocalWatchDir()))) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> Files.exists(Path.of(path + ".done")))
                    .toList();
        }
    }
}
