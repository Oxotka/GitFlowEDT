package dev.edt.gitflow.core;

import java.util.List;

public record OperationResult(Kind kind, String message, boolean workspaceChanged, boolean commitCreated,
    List<String> affectedPaths)
{
    public enum Kind
    {
        SUCCESS, NO_CHANGE, CANCELLED, CONFLICT, NEEDS_CONFIRMATION, NEEDS_NATIVE_MERGE,
        NEEDS_CHECKOUT_CLEANUP, ERROR
    }

    public OperationResult
    {
        if (message != null && message.endsWith(".") //$NON-NLS-1$
            && !message.substring(0, message.length() - 1).matches("(?s).*[.!?]\\s+.*")) //$NON-NLS-1$
            message = message.substring(0, message.length() - 1);
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
    }

    public OperationResult(Kind kind, String message)
    {
        this(kind, message, false, false, List.of());
    }

    public OperationResult(Kind kind, String message, boolean workspaceChanged)
    {
        this(kind, message, workspaceChanged, false, List.of());
    }

    public OperationResult(Kind kind, String message, boolean workspaceChanged, boolean commitCreated)
    {
        this(kind, message, workspaceChanged, commitCreated, List.of());
    }

    public boolean succeeded()
    {
        return kind == Kind.SUCCESS || kind == Kind.NO_CHANGE;
    }
}
