package org.saltations.monk;

import com.google.common.collect.Sets;
import lombok.Getter;

import java.util.Set;

@Getter
public enum ProjectType
{
    GIT(Sets.newHashSet(".git")),
    JAVA(Sets.newHashSet("target"))
    ;

    private final Set<String> foldersToExclude;

    ProjectType(Set<String> foldersToExclude)
    {
        this.foldersToExclude = foldersToExclude;
    }
}
