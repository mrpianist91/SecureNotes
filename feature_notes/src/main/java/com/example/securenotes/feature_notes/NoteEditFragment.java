package com.example.securenotes.feature_notes;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteDao;
import com.example.securenotes.core.NoteRepository;
import com.example.securenotes.core.AppDatabase;
import com.example.securenotes.feature_notes.databinding.FragmentNoteEditBinding;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.transition.MaterialSharedAxis;
import java.util.UUID;
import android.text.Html;
import android.text.Spanned;
import android.widget.AdapterView;
/**
 * Fragment di editing per una singola nota.
 * Permette di modificare titolo, testo, tag e proprietà temporanea della nota (cioè è possibile trasformarla in nota normale)
 * Salvataggio automatico su navigazione indietro (autosave).
 */

public class NoteEditFragment extends Fragment {
    private FragmentNoteEditBinding binding;
    private NotesViewModel viewModel;
    private Note editingNote;
    private boolean initialTempState = false; // stato iniziale "temporanea"

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Transizioni Material Shared Axis per entrata/uscita
        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
        setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.X,  false));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup
            container, Bundle savedInstanceState) {
        binding = FragmentNoteEditBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Ottiene il ViewModel condiviso (stesso di notesListFragment)
        // DB già aperto a login; ottiene DAO dall'istanza singleton
        NoteDao dao = AppDatabase.getInstance().noteDao();
        NoteRepository repo = new NoteRepositoryImpl(dao);
        viewModel = new ViewModelProvider(requireActivity(), new NotesViewModelFactory(repo)).get(NotesViewModel.class);
        // Recupera l'ID della nota passato tramite Safe Args (se presente)
        String noteId = getArguments() != null ? getArguments().getString("noteId") : null;
        if (noteId != null) {
        // Modalità "modifica" nota esistente
        // Tenta di ottenere la nota dal ViewModel (lista già caricata) o dal repository
            Note noteFromVM = viewModel.findNoteById(noteId);
            editingNote = (noteFromVM != null) ? noteFromVM : viewModel.getCurrentNote();
            if (editingNote == null) {
                 // Se non trovata in VM, carica in modo sincrono dal DB (evitare se possibile)
                editingNote = dao.getNoteById(noteId);
            }
            if (editingNote == null) {
                Toast.makeText(requireContext(), "Nota non trovata", Toast.LENGTH_SHORT).show();
                // Torna indietro se non trovata
                NavHostFragment.findNavController(this).popBackStack();
                return;
            }
        } else {
            // Modalità "creazione" nuova nota
            editingNote = new Note();
            editingNote.id = UUID.randomUUID().toString();//generiamo un id casuale con SecureRandom (.randomUUID())
            editingNote.title = "";
            editingNote.body = "";
            editingNote.tag = Note.TAG_FACCENDE; // Default prima categoria (ad es. "Faccende")
            editingNote.createdAt = System.currentTimeMillis();
            editingNote.expiresAt = 0; // 0 = non temporanea
        }

        // Salva lo stato iniziale come "non temporanea"
        initialTempState = (editingNote.expiresAt != 0);

        // Configura menu a tendina dei tag (spinner dropdown)
        String[] tagOptions = new String[]{ Note.TAG_FACCENDE, Note.TAG_LAVORO, Note.TAG_FAMIGLIA };
        //un adapter che mappa l’array di stringhe (tagOptions) in righe UI del dropdown, usando il layout di sistema simple_dropdown_item_1line (una riga di testo semplice per voce).
        //Il Context serve per inflatare il layout delle righe
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, tagOptions);
        /*collega il menù a tendina con la lista dei tag (tagOptions) al dropdownTag. Il dropdownTag è un AutoCompleteTextView (tipico dell’Exposed Dropdown Menu di Material 3 dentro un TextInputLayout).
          Collegando l’adapter, il campo mostrerà suggerimenti/lista a tendina con i tre tag quando l’utente interagisce.
          Guarda ExposedDropdownMenu.docx in cartella!*/
        binding.dropdownTag.setAdapter(adapter);
        // Se la nota ha un tag specifico, seleziona la voce (del dropdown menù) corrispondente
        /*Se si modifica una nota che ha già un tag (editingNote.tag non è null):
          Converte l’array tagOptions in una List temporanea per usare indexOf(...) e trovare l’indice del tag assegnato.
          Se l’indice è valido (>= 0), imposta il testo del dropdown al tag corrispondente.
          Il secondo parametro “false” evita che l’impostazione del testo inneschi automaticamente il filtro (di ricerca) dell’AutoCompleteTextView o apra il menu:
           imposta solo il valore/testo visualizzato. Esempio: editingNote.tag = Note.TAG_LAVORO → idx = 1 → viene mostrato “Lavoro” nel campo.*/
        if (editingNote.tag != null) {
            int idx = java.util.Arrays.asList(tagOptions).indexOf(editingNote.tag);
            if (idx >= 0) binding.dropdownTag.setText(tagOptions[idx], false);
        }
        // Popola i campi UI con i dati della nota
        binding.editTitle.setText(editingNote.title);
        // Converte il corpo HTML salvato in Spannable per mostrare formattazione
        if (editingNote.body != null && !editingNote.body.isEmpty()) {
            Spanned spanned = Html.fromHtml(editingNote.body, Html.FROM_HTML_MODE_LEGACY);
            binding.editBody.setText(spanned);
        } else {
            binding.editBody.setText("");
        }
        // Imposta switch "Nota temporanea" in base allo stato corrente
        binding.switchTemporary.setChecked(editingNote.expiresAt != 0);

        // Listener per pulsante Bold: applica <b> al testo selezionato
        binding.btnBold.setOnClickListener(v -> {
            int start = Math.min(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            int end   = Math.max(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            if (start < end) {
                Editable text = binding.editBody.getText();
                text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        });

        // Listener per pulsante Italic: applica <i> al testo selezionato
        binding.btnItalic.setOnClickListener(v -> {
            int start = Math.min(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            int end   = Math.max(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            if (start < end) {
                Editable text = binding.editBody.getText();
                text.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        });

        // Listener per pulsante Underline: applica <u> al testo selezionato
        binding.btnUnderline.setOnClickListener(v -> {
            int start = Math.min(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            int end   = Math.max(binding.editBody.getSelectionStart(), binding.editBody.getSelectionEnd());
            if (start < end) {
                Editable text = binding.editBody.getText();
                text.setSpan(new UnderlineSpan(),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        });

        // Gestione navigazione indietro con autosave
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        saveAndExit();
                    }
                });
        // 2.Intercetta la freccia "Up" nella Toolbar (App Bar)
        // Recuperiamo la Toolbar dall'Activity.
        // Cerchiamo la risorsa chiamata "toolbar" di tipo "id" nel package dell'applicazione ospitante.
        int toolbarId = getResources().getIdentifier("toolbar", "id", requireContext().getPackageName());
        if (toolbarId != 0) {
            MaterialToolbar toolbar = requireActivity().findViewById(toolbarId);
            if (toolbar != null) {
                toolbar.setNavigationOnClickListener(v -> {
                    // Simula la pressione del tasto Back.
                    // Questo attiverà il callback definito al punto 1, eseguendo saveAndExit().
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                });
            }
        }
    }

    /** Salva la nota in editing e torna alla lista. */
    private void saveAndExit() {
        // Legge i valori attuali dai campi UI
        String titleInput = binding.editTitle.getText().toString().trim();//.trim() elimina gli spazi bianchi iniziali e finali
        String bodyInputPlain = binding.editBody.getText().toString(); // testo senza markup
        // Converte il contenuto formattato in HTML per salvataggio persistente
        Spanned spanned = binding.editBody.getText();
        String bodyHtml = Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL);
        // Determina il titolo da salvare:
        String finalTitle;
        if (!titleInput.isEmpty()) {
            // Se l’utente ha inserito manualmente un titolo, usa quello (troncando se troppo lungo)
            finalTitle = titleInput;
        } else {
            // Altrimenti usa la prima riga non vuota del corpo come titolo
            finalTitle = "";
            String[] lines = bodyInputPlain.split("\n");
            for (String line : lines) {
                if (line.trim().length() > 0) {
                    finalTitle = line.trim();
                    break;
                }
            }
                if (finalTitle.isEmpty()) {
                    finalTitle = "Nuova Nota";
                }
            }

        // Troncamento del titolo a lunghezza ragionevole (es. 50 caratteri)
        if (finalTitle.length() > 50) {
            finalTitle = finalTitle.substring(0, 50) + "...";
        }

        // Determina il tag selezionato (dal dropdown menu)
        String selectedTag = binding.dropdownTag.getText().toString().trim();
        if (selectedTag.isEmpty()) {
            selectedTag = Note.TAG_FACCENDE; // default fallback
        }

        // Aggiorna l'oggetto Note con i nuovi valori
        editingNote.title = finalTitle;
        editingNote.body = bodyHtml;
        editingNote.tag = selectedTag;
        // Imposta o aggiorna expiresAt a seconda dello stato dello switch e precedente
        boolean tempSwitchChecked = binding.switchTemporary.isChecked();
        if (initialTempState && tempSwitchChecked) {
             // Era temporanea e resta temporanea -> mantieni il vecchio expiresAt
             // (non estendere la durata oltre il tempo originario)
        } else if (initialTempState && !tempSwitchChecked) {
            // Era temporanea e l'utente l'ha disattivata -> cancella scadenza
            editingNote.expiresAt = 0;
        } else if (!initialTempState && tempSwitchChecked) {
            // Non era temporanea, ora l'utente l'ha attivata -> imposta nuova scadenza 24h da ora
            editingNote.expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        } else {
            // Non era temporanea e rimane non temporanea -> 0 (già 0)
            editingNote.expiresAt = 0;
        }
        /*getArguments() restituisce il Bundle associato al Fragment,
         cioè il “pacchetto” di dati passato alla sua creazione (di solito via Navigation Component/Safe Args o Fragment.setArguments(Bundle)).
          Può essere null se nessuno ha fornito argomenti al fragment (tipico nel caso “nuova nota”, quando si apre l’editor senza ID).*/
         if (getArguments() != null && getArguments().containsKey("noteId")) {
             // Nota esistente: effettua update
            viewModel.updateNote(editingNote);
         } else {
             // Nota nuova: aggiungi al database
             viewModel.addNote(editingNote);
         }
         // Torna alla lista note
        NavHostFragment.findNavController(NoteEditFragment.this).popBackStack();
    }
}
