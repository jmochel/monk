package org.saltations.monk;

import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import io.micronaut.configuration.picocli.PicocliRunner;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
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
            names = {"-fnr","--file-name-regex"},
            description = "Regular expression templates that convert matching file names to new file names. This is modeled as three strings separated by a comma." +
                          " such as -fnr \"([A-Za-z]+).java\",\"<1>.cxx\",\"Sample.java\"" +
                          " The first string is a regular expression that should match the file name. It uses '(' and ')' to  denote groups that match that can be used in replacement templates." +
                          " Second string is a template that denotes what the new file name should look like with a '<1>' For the first replacement, '<2>' For the second replacement, and so on." +
                          " The last string is a sample file name that can be used internally to test the matching and replacement process.",
            converter = FileNameTransformConverter.class
    )
    private Set<FileNameTransform> fileNameTransforms = new HashSet<>();

    @Option(
            required = false,
            names = {"-cc","--content-change"},
            description = "Content change search, and replacement strings. This is modeled as 2 strings separated by a comma." +
                    " such as -cc \"FindThis\",\"ReplaceWithThis\" " +
                    " First string is a Non-regular expression string to search for." +
                    " Second string is the string to replace it with." ,
            converter = FileContentTransformConverter.class
    )
    private Set<FileContentTransform> fileContentTransforms = new HashSet<>();

    public static void main(String[] args) throws Exception {
        PicocliRunner.run(MonkCommand.class, args);
    }

    public void run()
    {
        var currentWorkingFolderPath = Paths.get("");
        this.sourceFolderPath = this.sourceFolderPath.normalize();
        this.targetFolderPath = this.targetFolderPath.normalize();

        log.info("\n\tCurrent working directory: [{}]\n\tSource folder: [{}]\n\tTarget folder: [{}]",
                currentWorkingFolderPath.toAbsolutePath(), sourceFolderPath.toAbsolutePath(), targetFolderPath.toAbsolutePath());

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

            Files.walkFileTree(sourceFolderPath, new ExcludeCopyAndTransform(sourceFolderPath, targetFolderPath , foldersToExclude, fileNameTransforms, fileContentTransforms));
        }
        catch (IOException e)
        {
            log.error("", e);
        }
    }

    static class FileNameTransformConverter implements CommandLine.ITypeConverter<FileNameTransform>
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
            var numOfSlotsToMatch = matcher.groupCount();

            for (int i = 1; i <= numOfSlotsToMatch; i++)
            {
                var replacement = matcher.group(i);
                st.add(Integer.toString(i),replacement);
            }

            var rendered =st.render().toString();
            log.debug("Filename substitution appears to be correct. Translates [{}] to [{}]", sample, rendered);

            return new FileNameTransform(searchPattern, templateValue);
        }
    }

    static class FileContentTransformConverter implements CommandLine.ITypeConverter<FileContentTransform>
    {
        public FileContentTransform convert(String value) throws Exception {

            // Break the input into 2 parts

            var values = Splitter.on(',').splitToList(value);
            checkArgument(values.size() == 2,"Unable to convert pattern: %s to a search string and a replace string", value);

            var searchValue = values.get(0);
            var replaceValue = values.get(1);

            return new FileContentTransform(searchValue, replaceValue);
        }
    }

    @Data
    static class FileNameTransform
    {
        private final Pattern searchPattern;
        private final String template;

        public boolean matches(String fileName)
        {
            var matches = searchPattern.matcher(fileName).matches();

            return matches;

        }

        public String createNewFileName(String fileName)
        {
            var matcher = searchPattern.matcher(fileName);
            matcher.matches();

            // Turn matches into map entries

            ST st = new ST(template);
            var numOfSlotsToMatch = matcher.groupCount();

            for (int i = 1; i <= numOfSlotsToMatch; i++)
            {
                var replacement = matcher.group(i);
                st.add(Integer.toString(i),replacement);
            }

            return st.render().toString();
        }
    }

    @Data
    static class  FileContentTransform {
        private final String searchFor;
        private final String replaceWith;

        public boolean matches(String content)
        {
            return content.contains(searchFor);
        }

        public String modifyWithReplacement(String content)
        {
            return content.replaceAll(searchFor, replaceWith);
        }
    }

    @RequiredArgsConstructor
    static class ExcludeCopyAndTransform implements FileVisitor<Path>
    {
        private final Path sourceFolderPath;
        private final Path targetFolderPath;
        private final Set<String> foldersToExclude;
        private final Set<FileNameTransform> fileNameTransforms;
        private final Set<FileContentTransform> fileContentTransforms;

        @Override
        public FileVisitResult preVisitDirectory(Path sourceFolder, BasicFileAttributes attrs) throws IOException
        {
            if (foldersToExclude.contains(sourceFolder.getFileName().toString()))
            {
                log.info("Skipping Folder: [{}]", sourceFolder.toString());
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
        public FileVisitResult visitFile(Path sourceFilePath, BasicFileAttributes attrs) throws IOException
        {
            // Transform file name if necessary

            var sourceFileName = sourceFilePath.getFileName().toString();
            var potentialTargetFileName = fileNameTransforms.stream()
                                                            .filter(fnt -> fnt.matches(sourceFileName))
                                                            .map(fnt -> fnt.createNewFileName(sourceFileName))
                                                            .findFirst();

            var targetFileName = potentialTargetFileName.orElse(sourceFileName);
            var targetFilePath = targetFolderPath.resolve(sourceFolderPath.relativize(sourceFilePath).resolveSibling(targetFileName));

            log.info("Copying [{}] to [{}]", sourceFilePath, targetFilePath);

            var content = Files.readString(sourceFilePath);
            String newContent = content;

            for (FileContentTransform transform : fileContentTransforms)
            {
                newContent = transform.modifyWithReplacement(newContent);
            }

            Files.writeString(targetFilePath, newContent);

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path sourceFile, IOException exc) throws IOException
        {
            return FileVisitResult.TERMINATE;
        }
    }


}
