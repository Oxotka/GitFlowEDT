package dev.edt.gitflow.core;

public record OperationResult(Kind kind, String message)
{
    public enum Kind { SUCCESS, NO_CHANGE, CONFLICT, NEEDS_CONFIRMATION, ERROR }

    public boolean succeeded()
    {
        return kind == Kind.SUCCESS || kind == Kind.NO_CHANGE;
    }
}
