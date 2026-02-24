package com.example.securenotes;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test di sicurezza per verificare che la sessione sia bloccata dopo il timeout
 * o dopo che l'app è andata in background.
 *
 * Problema: il SessionObserver tiene tutto lo stato in variabili statiche (usate in altrettanti metodi statici)
 * Dobbiamo accedere alle variabili tramite reflection così da forzare l'accesso anche ai campi private a runtime
 * e non richiedendo l'uso di un'Activity o del NavController. Le chiamate alla Navigation diventano inoperative
 * perchè il NavController è settato a null in setUp().
 *
 */

//@RunWith(AndroidJUnit4.class) dice al sistema di test di usare il runner di Android JUnit 4, che sa come installare il test su un dispositivo e
//gestire il ciclo di vita Android.
@RunWith(AndroidJUnit4.class)
public class SessionTimeoutSecurityTest {

    /** Applichiamo un timeout di test da 300 millisecondi  */
    private static final long SHORT_TIMEOUT_MS = 300L;

    /** Usiamo questa variabile di 60 secondi nei test in cui vogliamo testare altri componenti
     * o aspetti inerenti la sessione (come il comportamento in seguito a background dell'app) */
    private static final long LONG_TIMEOUT_MS = 60_000L;

    /**
     * Viene passato come "finto" (stub) LifecycleOwner (cioè il Processo/ProcessLifecycleOwner)
     * nei metodi onCreate() e onStop() ereditati da SessionObserver dal DefaultLifecycleObserver
     * NB non viene mai usato ma è necessario per rispettare la firma.
     */
    private LifecycleOwner stubOwner;

    /** L'istanza usata per invocare le callback (onStart(), onStop()) */
    private SessionObserver sessionObserver;


    // Setup / teardown

    @Before//annotazione che sta per "esegui questo metodo prima di ogni singolo test".
    //Serve a "ripulire" il SessionObserver dopo ogni singolo test
    public void setUp() throws Exception {
        // creiamo un nuovo SessionObserver
        // passando "null" come NavController (non viene usato durante i test, non c'è activity reale)
        // e il timeout lungo (1 min)
        sessionObserver = new SessionObserver(null, LONG_TIMEOUT_MS);

        // Cancella la callback (il Runnable che gestisce il timeout) in attesa sull'Handler:
        // se un test precedente ha avviato un timer lo cancella qui per evitare interferenze.
        cancelPendingTimeout();

        // setStaticBoolean è un metodo (che si trova più sotto) per fare il reset (portarli a false/stato iniziale) dei valori booleani ottenuti tramite riflessione
        setStaticBoolean("isSessionValid", false);

        //Creiamo il "finto" LifecycleOwner (ProcessLifecycleOwner), che richiede di implementare il metodo getLifecycle()
        stubOwner = new LifecycleOwner() {
            @NonNull
            @Override
            public Lifecycle getLifecycle() {
                return new LifecycleRegistry(this);
            }
        };
    }

    @After
    public void tearDown() throws Exception {

        cancelPendingTimeout();
        setStaticBoolean("isSessionValid", false);
    }


    // Test 1 — timeout per inattività
    /**
     * Obiettivo primario: dopo "sessionTimeoutMs" millisecondi che l'utente non interagisce,
     * la sessione deve essere automaticamente invalidata.
     */
    @Test
    public void inactivityTimeoutInvalidatesSession() throws Exception {
        SessionObserver.startSession(SHORT_TIMEOUT_MS);//avviamo la sessione per 300 millisecondi
        assertTrue("La sessione deve essere validata subito dopo lo Start", isSessionValid());

        // Mettiamo il thread in attesa per 700 millisecondi (l'Handler dovrebbe postare il TimeoutRunnable che impone il timeout).
        Thread.sleep(SHORT_TIMEOUT_MS + 400L);
        //  waitForIdleSync() è un metodo di Android che blocca il thread corrente finché la coda messaggi del main thread non è completamente
        //  vuota. È la garanzia che tutto il codice postato sull'Handler (quindi il Runnable che impone il timeout) è stato eseguito prima di leggere isSessionValid (guarda sotto)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertFalse(
                "La sessione deve essere invalidata dopo che i 300 millisecondi sono passati..."
                        + SHORT_TIMEOUT_MS + " ms passati",
                isSessionValid()
        );
    }

    // Test 2 — zero-trust background
    /**
     * Quando l'app va in background (onStop()), la sessione deve essere invalidata
     * immediatamente
     */
    @Test
    public void backgroundingInvalidatesSession() throws Exception {
        SessionObserver.startSession(LONG_TIMEOUT_MS);
        assertTrue("La sessione deve essere valida subito dopo lo start", isSessionValid());

        sessionObserver.onStop(stubOwner); // simula l'app che va in background

        assertFalse("La sessione deve essere invalidata subito dopo che l'app va in background", isSessionValid());
    }


    //Reflection helpers
    //Facciamo il "return" di isSessionValid() tramite reflection
    private boolean isSessionValid() throws Exception {
        Field f = SessionObserver.class.getDeclaredField("isSessionValid");
        f.setAccessible(true);
        return f.getBoolean(null);
    }
    //settiamo il valore di una variabile booleana tramite reflection
    private void setStaticBoolean(String fieldName, boolean value) throws Exception {
        Field f = SessionObserver.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    //Metodo che recupera l'handler del Runnable, e il Runnable stesso, che contiene la lambda che invalida la sessione, se l'utente non tocca schermo per "x" minuti
    //Il Runnable viene bloccato.
    private void cancelPendingTimeout() throws Exception {
        Field handlerField = SessionObserver.class.getDeclaredField("timeoutHandler");
        handlerField.setAccessible(true);//nella classe è "private". Va reso accessibile.
        Handler handler = (Handler) handlerField.get(null);//leggiamo il valore. Si passa "null" perchè il campo è "static"

        Field runnableField = SessionObserver.class.getDeclaredField("timeoutRunnable");//la lambda che invalida la sessione se l'utente non tocca schermo per "x" minuti
        runnableField.setAccessible(true);
        Runnable runnable = (Runnable) runnableField.get(null);

        handler.removeCallbacks(runnable);
    }
}