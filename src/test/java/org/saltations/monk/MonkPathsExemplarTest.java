package org.saltations.monk;

import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MonkPathsExemplarTest
{

    @Test
    public void series1() throws Exception {

        var sourceFilePath = Paths.get("root","sub1", "sub2", "file1.txt");

        var sourceFileName = sourceFilePath.getFileName();
        var targetFileName = Paths.get("newFile.txt");
        System.out.println(sourceFilePath.resolveSibling(targetFileName));

    }
}
