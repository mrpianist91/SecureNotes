package com.example.securenotes.core;

/** Wrapper per eventi LiveData: il contenuto viene "consumato" una sola volta. */
public class Event<T> {
    private final T content;
    private boolean hasBeenHandled = false;

    public Event(T content) { this.content = content; }

    /** Ritorna il contenuto se non ancora gestito, altrimenti null. */
    public T getContentIfNotHandled() {
        if (hasBeenHandled) return null;
        hasBeenHandled = true;
        return content;
    }

    /** Permette di ottenere il contenuto anche se già gestito. */
    public T peekContent() { return content; }
}
