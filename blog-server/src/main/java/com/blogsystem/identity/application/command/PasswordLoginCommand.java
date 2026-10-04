package com.blogsystem.identity.application.command;

public record PasswordLoginCommand(String phone, String password) {
    @Override
    public String toString() {
        return "PasswordLoginCommand[REDACTED]";
    }
}

