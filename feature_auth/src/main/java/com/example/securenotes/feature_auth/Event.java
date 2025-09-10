package com.example.securenotes.feature_auth;

/** Wrapper per eventi LiveData: il contenuto viene consumato una sola volta. */
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

    /** Permette di sbirciare il contenuto anche se già gestito. */
    public T peekContent() { return content; }
}
