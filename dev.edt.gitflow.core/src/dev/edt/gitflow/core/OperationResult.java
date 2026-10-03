package dev.edt.gitflow.core;

public record OperationResult(Kind kind, String message, boolean workspaceChanged, boolean commitCreated)
{
    public enum Kind { SUCCESS, NO_CHANGE, CONFLICT, NEEDS_CONFIRMATION, ERROR }

    public OperationResult(Kind kind, String message)
    {
        this(kind, message, false, false);
    }

    public OperationResult(Kind kind, String message, boolean workspaceChanged)
    {
        this(kind, message, workspaceChanged, false);
    }

    public boolean succeeded()
    {
        return kind == Kind.SUCCESS || kind == Kind.NO_CHANGE;
    }
}
