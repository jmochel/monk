package org.saltations.monk;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import io.micronaut.configuration.picocli.PicocliRunner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.System.exit;

@Slf4j(topic = "Monk")
@Command(name = "monk", header = {
        """
            ##     ##  #######  ##    ## ##    ##\s
            ###   ### ##     ## ###   ## ##   ## \s
            #### #### ##     ## ####  ## ##  ##  \s
            ## ### ## ##     ## ## ## ## #####   \s
            ##     ## ##     ## ##  #### ##  ##  \s
            ##     ## ##     ## ##   ### ##   ## \s
            ##     ##  #######  ##    ## ##    ##\s
        """
},
description = "Monkish Copier of directories as templates",
mixinStandardHelpOptions = true)
public class MonkCommand implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "...")
    boolean verbose;

    @Option(
        required = true,
        names = {"-s","--source"},
        description = "Path from the current directory to the folder being used as a template"
    )
    private Path sourceFolderPath;

    @Option(
            required = true,
            names = {"-t","--target"},
            description = "Path from the current directory to the folder created. Includes the name of the folder to br created"
    )
    private Path targetFolderPath;

    @Option(
            required = false,
            names = {"-p","--projs"},
            description = "Set up an enumerations that tell us what are the types of files we will encounter"
    )
    private Set<ProjectType> projectTypes = Sets.newHashSet(ProjectType.GIT, ProjectType.JAVA);

    @Option(
            required = false,
            names = {"--regex"},
            converter = TransformExpressionConverter.class
    )
    private Set<FileNameTransform> regexes = new HashSet<>();

    public static void main(String[] args) throws Exception {
        PicocliRunner.run(MonkCommand.class, args);
    }

    public void run()
    {
        var currentWorkingFolderPath = Paths.get("");
        this.sourceFolderPath = this.sourceFolderPath.normalize();
        this.targetFolderPath = this.targetFolderPath.normalize();

        log.info("Current working directory: [{}]", currentWorkingFolderPath.toAbsolutePath());
        log.info("Source folder: [{}]", sourceFolderPath.toAbsolutePath());
        log.info("Target folder: [{}]", targetFolderPath.toAbsolutePath());


        regexes.forEach(x -> System.out.println(x));

        // Check preconditions

        if (sourceFolderPath.compareTo(targetFolderPath) == 0)
        {
            log.error("Source folder and target folder cannot be the same");
            exit(1);
        }

        if (currentWorkingFolderPath.compareTo(sourceFolderPath) == 0)
        {
            log.error("Current working folder and the source folder cannot be the same");
            exit(1);
        }

        if (currentWorkingFolderPath.compareTo(targetFolderPath) == 0)
        {
            log.error("Current working folder and the target folder cannot be the same");
            exit(1);
        }

        // Confirm source folder exists

        if (!sourceFolderPath.toFile().exists())
        {
            log.error("Source folder [{}] does not exist.", sourceFolderPath.toAbsolutePath());
            exit(1);
        }

        // Gather the folders to be excluded

        var foldersToExclude = projectTypes.stream()
                           .flatMap(pt -> pt.getFoldersToExclude()
                           .stream()).collect(Collectors.toSet());

        // Recursively copy all files

        try {

            Files.walkFileTree(sourceFolderPath, new ExcludeAndCopy(sourceFolderPath, targetFolderPath , foldersToExclude));
        }
        catch (IOException e)
        {
            log.error("", e);
        }
    }

    static class TransformExpressionConverter implements CommandLine.ITypeConverter<FileNameTransform>
    {
        public FileNameTransform convert(String value) throws Exception {

            // Break the input into 3 parts

            var values = Splitter.on(',').splitToList(value);
            checkArgument(values.size() == 3,"Unable to convert pattern: %s to two regexes and a sample", value);

            var searchValue = values.get(0);
            var templateValue = values.get(1);
            var sample = values.get(2);

            var searchPattern = Pattern.compile(searchValue); // Confirm that the two patterns compile
            var matcher = searchPattern.matcher(sample);      // Confirm that the search pattern will match the sample

            if(!matcher.matches())
            {
                throw new IllegalArgumentException("Pattern: " + value + " does not match to the sample");
            }

            // Confirm we can apply the replacement to the matched sample and transform it

            // Turn matches into map entries

            ST st = new ST(templateValue);
            var numberOfReplacementSlots = st.getAttributes().size();

            var max = matcher.groupCount();

            for (int i = 1; i <= max; i++)
            {
                var replacement = matcher.group(i);
                st.add(Integer.toString(i),replacement);
            }

            var rendered =st.render().toString();

            return new FileNameTransform(searchPattern, templateValue);
        }
    }
    record FileNameTransform(Pattern searchExpression, String template){}
    record FileContentTransform(String searchExpression, String replacementValue){}

    @RequiredArgsConstructor
    static class ExcludeAndCopy implements FileVisitor<Path>
    {
        private final Path sourceFolderPath;
        private final Path targetFolderPath;
        private final Set<String> foldersToExclude;

        @Override
        public FileVisitResult preVisitDirectory(Path sourceFolder, BasicFileAttributes attrs) throws IOException
        {
            if (foldersToExclude.contains(sourceFolder.getFileName().toString()))
            {
                log.info("Skipping folder : [{}]", sourceFolder.toString());
                return FileVisitResult.SKIP_SUBTREE;
            }

            /*
             * If this is the top level source folder, create the top level target folder, Otherwise create the relative folder in the target
             */

            var targetFolder = sourceFolder.compareTo(sourceFolderPath) == 0 ?
                    targetFolderPath : targetFolderPath.resolve(sourceFolderPath.relativize(sourceFolder));

            if (!targetFolder.toAbsolutePath().toFile().exists())
            {
                Files.createDirectory(targetFolder.toAbsolutePath());
                System.out.println("Created [" + targetFolder.toAbsolutePath() + "]");
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path sourceFolder, IOException exc) throws IOException
        {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path sourceFile, BasicFileAttributes attrs) throws IOException
        {
            log.info("VISITING FILE [{}]", sourceFile);
            log.info("SHOULD COPY FILE [{}] to [{}]", sourceFile, targetFolderPath.resolve(sourceFolderPath.relativize(sourceFile)));

            var targetFile = targetFolderPath.resolve(sourceFolderPath.relativize(sourceFile));
            Files.copy(sourceFile, targetFile);

            File toBeModified =  targetFile.toFile();





            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path sourceFile, IOException exc) throws IOException
        {
            return FileVisitResult.TERMINATE;
        }
    }


}
