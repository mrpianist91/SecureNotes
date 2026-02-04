package com.example.securenotes.feature_notes;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.securenotes.core.AppDatabase;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteDao;
import com.example.securenotes.core.NoteRepository;
import com.example.securenotes.feature_notes.databinding.FragmentNotesListBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.transition.MaterialSharedAxis;

public class notesListFragment extends Fragment implements NoteAdapter.OnNoteClickListener {
    private FragmentNotesListBinding binding;
    private NotesViewModel viewModel;
    //“adatta” la List<Note> presa dal ViewModel, per poterla inserire nelle (Recycler)View
    private NoteAdapter adapter;

    public notesListFragment() { /* costruttore vuoto richiesto */ }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true); //setHasOptionsMenu(true); in un Fragment dice al sistema: “questo fragment vuole contribuire al menu dell’Activity (o dell’AppBar)”
        // Imposta la transizione per l'uscita (navigazione verso editor)
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ true));
        setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ false));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentNotesListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);
        // Inizializzazione dipendenze (in un’app reale usare DI/Hilt)
        // Ottiene database esistente (DB già aperto nel LoginFragment)
        NoteDao noteDao = AppDatabase.getInstance().noteDao();
        NoteRepository repository = new NoteRepositoryImpl(noteDao);
        NotesViewModelFactory factory = new NotesViewModelFactory(repository);
        // Condivide lo stesso ViewModel con l'Activity per poterlo riutilizzare nell'editor
        viewModel = new ViewModelProvider(requireActivity(), factory).get(NotesViewModel.class);
        // Imposta RecyclerView e adapter
        adapter = new NoteAdapter(this); // 'this' implementa OnNoteClickListener, nel senso:
        // la classe notesListfragment (questa/this classe) implementa (implements) NoteAdapter.OnNoteClickListener (guarda l’intestazione della classe),
        // quindi è, a tutti gli effetti una OnNoteClickListener. Se infatti vediamo il costruttore del NoteAdapter, vedremo che vuole un OnNoteClickListener come argomento

        //Il “LayoutManager” è uno dei componenti fondamentali del RecyclerView: la sua unica responsabilità è posizionare gli elementi
        //sullo schermo; si occupa anche delle animazioni di scorrimento
        binding.rvNotesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvNotesList.setAdapter(adapter);
        // Osserva i cambiamenti della lista di note filtrata gestita dal viewmodel
        viewModel.notes.observe(getViewLifecycleOwner(), notes -> {
            adapter.setNotes(notes);
            // (Possibile gestione UI se lista vuota, es. mostrare testo "Nessuna nota")
            // Toggle empty view vs RecyclerView
            boolean isEmpty = (notes == null || notes.isEmpty());
            binding.tvEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            binding.rvNotesList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });
        // Swipe-to-delete: configura ItemTouchHelper per gestire swipe su
        // elementi RecyclerView
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            private Note swipedNote;
            private int swipedPosition;

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                // Nessuna funzionalità di drag & drop reorder, quindi false
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                swipedPosition = viewHolder.getAdapterPosition();//individua (torna) la “posizione” nella listView, della nota che stiamo cancellando
                swipedNote = adapter.getNoteAt(swipedPosition);//torna la nota nella posizione data…il metodo getNoteAt() va definito nella classe dell’adapter
                // Mostra dialog di conferma eliminazione
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Eliminare la nota?")
                        .setMessage("Questa nota sarà eliminata definitivamente.")
                        .setNegativeButton("Annulla", (dialog, which) -> {
                            // Annulla: ripristina l'elemento swippato
                            /*Dopo onSwiped(...) la cella è traslata/attenuata (in stato “dismiss”).
                             Se decidi di non eliminarla (utente preme Annulla), devi ripristinare la view.
                            adapter.notifyItemChanged(pos) dice alla RecyclerView:
                             “l’item alla posizione pos è cambiato” → rialloca e richiama onBindViewHolder per quell’item,
                              azzerando la traduzione/alpha (lo “sbiadimento”) applicato dallo swipe. Risultato: la riga torna visibile al suo posto,
                               cliccabile e con lo stato UI pulito.*/
                            adapter.notifyItemChanged(swipedPosition);
                        })
                        .setPositiveButton("Elimina", (dialog, which) -> {
                            // Conferma: cancella la nota dal ViewModel/DB
                            viewModel.deleteNote(swipedNote);
                            // Aggiornamento lista avverrà automaticamente via LiveData
                        })
                        .setOnDismissListener(dialog -> {
                            // Se il dialog viene chiuso senza scelta esplicita, ripristina elemento
                            if (swipedNote != null) {
                                adapter.notifyItemChanged(swipedPosition);
                            }
                        })
                        .show();
            }
        });

        helper.attachToRecyclerView(binding.rvNotesList);
        // FloatingActionButton per aggiungere nuova nota
        binding.fabAddNote.setOnClickListener(v -> {
            // Crea un nuovo fragment NoteEdit senza passare ID (nota nuova)
            NavHostFragment.findNavController(this).navigate(R.id.action_notesListFragment_to_noteEditFragment);
        });
    }

    //Evento di click (apertura) di una nota
    @Override
    public void onNoteClick(@NonNull Note note) {
        // All’evento di click su una nota esistente: passa l’ID della nota a NoteEditFragment tramite Safe Args
        Bundle args = new Bundle();
        args.putString("noteId", note.id);
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_notesListFragment_to_noteEditFragment, args);
        // Salva anche la nota selezionata nel ViewModel condiviso (per accesso diretto opzionale)
        viewModel.setCurrentNote(note);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_notes_list, menu);
        // Forza l'esclusività (un solo checked per volta) nel gruppo dei filtri/tag
        menu.setGroupCheckable(R.id.group_filters, /*checkable=*/true, /*exclusive=*/true);
        // Configura SearchView per ricerca istantanea
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();//Restituisce la View associata a quell’item di menu (l’“action view”). Se l’item è definito con app:actionViewClass="androidx.appcompat.widget.SearchView", getActionView() torna una SearchView concreta
        searchView.setQueryHint("Cerca nelle note...");//Mostra un placeholder quando il campo è vuoto
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                // Nasconde la tastiera al submit
                //quando l’utente preme "invio", viene chiamato clearFocus() per togliere il focus (in genere chiude la tastiera, specie se l’item è “collapsable”).
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                //ad ogni cambio di testo, aggiorni searchQuery nel ViewModel. Grazie a MediatorLiveData/switchMap, ciò ri-esegue la query filtrata e la lista si aggiorna in tempo reale.
                viewModel.setSearchQuery(newText);
                return true;
            }
        });
        // Imposta menu filtri tag (segna inizialmente "Tutti" come selezionato)
        MenuItem allItem = menu.findItem(R.id.filter_all);
        allItem.setChecked(true);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        /*switch (item.getItemId()) {
            case R.id.filter_all:
                viewModel.setSelectedTag(Note.TAG_ALL);
                item.setChecked(true);
                return true;
            case R.id.filter_faccende:
                viewModel.setSelectedTag(Note.TAG_FACCENDE);
                item.setChecked(true);
                return true;
            case R.id.filter_lavoro:
                viewModel.setSelectedTag(Note.TAG_LAVORO);
                item.setChecked(true);
                return true;
            case R.id.filter_famiglia:
                viewModel.setSelectedTag(Note.TAG_FAMIGLIA);
                item.setChecked(true);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }*/

        // Gestisce click sul filtro tag (evita switch su R.id non-final)
        final int id = item.getItemId();
        if (id == R.id.filter_all || id == R.id.filter_faccende
                || id == R.id.filter_lavoro || id == R.id.filter_famiglia) {
            final String selected;
            if (id == R.id.filter_all) {
                selected = Note.TAG_ALL;
            } else if (id == R.id.filter_faccende) {
                selected = Note.TAG_FACCENDE;
            } else if (id == R.id.filter_lavoro) {
                selected = Note.TAG_LAVORO;
            } else {
                selected = Note.TAG_FAMIGLIA;
            }
            viewModel.setSelectedTag(selected);
            // Segna questo item come checked e deseleziona gli altri
            item.setChecked(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}




