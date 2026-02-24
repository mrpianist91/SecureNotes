package com.example.securenotes.feature_notes;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.securenotes.core.Note;
import com.example.securenotes.feature_notes.databinding.ItemNoteBinding;
import com.google.android.material.chip.Chip;
import java.util.ArrayList;
import java.util.List;
/**

 * Adapter per la RecyclerView delle note.
 * Gestisce il binding dei dati di ciascuna nota nella lista.
 */
public class NoteAdapter extends RecyclerView.Adapter<NoteAdapter.NoteViewHolder> {//la classe NoteViewHolder (nested) viene definita dentro questa (NoteAdapter) medesima classe (vedrai a breve)
    public interface OnNoteClickListener {
        void onNoteClick(@NonNull Note note);
    }
    private final OnNoteClickListener clickListener;
    private List<Note> notes = new ArrayList<>();

    //NB notesListFragment implementa (implements) l’interfaccia NoteAdapter.OnNoteClickListener.
    //Infatti dentro notesListFragment viene passato “this” come argomento dell’istanza di NoteAdapter
    public NoteAdapter(OnNoteClickListener clickListener) {
        this.clickListener = clickListener;
    }
    //viene richiamato dentro l’observer del NotesViewModel, che notifica i cambiamenti delle note
    public void setNotes(List<Note> newNotes) {
        this.notes = newNotes != null ? newNotes : new ArrayList<>();
        notifyDataSetChanged();//notifica un cambiamento nella lista delle note
    }
    public Note getNoteAt(int position) {
        return notes != null && position >= 0 && position < notes.size() ?
                notes.get(position) : null;
    }

    //Chiamato quando il RecyclerView ha bisogno di un nuovo ViewHolder.
    //Infla il layout dell'item e crea un'istanza del ViewHolder.
    @NonNull
    @Override
    public NoteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemNoteBinding binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new NoteViewHolder(binding);
    }

    /*Questo metodo viene chiamato continuamente. Il suo compito è prendere un ViewHolder riciclato e popolarlo con i dati corretti per una nuova posizione.
     Esempio: Stai scorrendo la lista. La Nota #1 esce dalla parte superiore dello schermo.
     Il suo ViewHolder non viene distrutto, ma messo nella riserva. Appena la Nota 11 deve apparire in basso,
     il sistema prende il ViewHolder riciclato della Nota #1 e chiama onBindViewHolder() per aggiornare i suoi TextView con il titolo e il testo della Nota 11.*/
    @Override
    public void onBindViewHolder(@NonNull NoteViewHolder holder, int
            position) {
        Note note = notes.get(position);
        holder.titleText.setText(note.title != null && ! note.title.isEmpty() ? note.title : "Nuova Nota");
        // Imposta il Chip con il tag e con l'icona orologio se nota temporanea
        holder.tagChip.setText(note.tag);
        if (note.expiresAt != 0) {//se nota temporanea..mettiamo l’icona dell’orologio
            holder.tagChip.setChipIcon(ContextCompat.getDrawable(holder.tagChip.getContext(), R.drawable.ic_clock));
            holder.tagChip.setChipIconVisible(true);
        } else {//se l'icona NON è temporanea...
            /*Importante: non nascondono l’intero Chip. Nascondono solo l’icona dell’orologio. Il testo del tag resta visibile!
              Dal punto di vista visivo, se la nota è temporanea: Chip con testo del tag + icona orologio (tinta coerente col tema).
              Se è non temporanea: Chip con solo testo del tag; nessun rientro “fantasma” perché l’icona è nulla e chipIconVisible è false.*/
            holder.tagChip.setChipIcon(null);
            holder.tagChip.setChipIconVisible(false);
        }
//Settiamo il ClickListener e, in conseguenza, il metodo (.onNoteClick()) da richiamare nella callback in onClick(v)
        holder.itemView.setOnClickListener(v -> {
            //il clickListener viene inizializzato nel costruttore del NoteAdapter (guarda sopra)…in pratica è il notesListFragment.java
            if (clickListener != null) clickListener.onNoteClick(note);
        });
        /*in riferimento al codice sopra, in pratica, siccome siamo nell’OnBindViewHolder, su ogni riga (itemView) della lista di note, viene agganciato un OnClickListener.
         Quando l’utente tocca la riga, RecyclerView chiama onClick(View v) passando come v la View cliccata, cioè holder.itemView.
          La lambda riceve v (parametro obbligatorio per la firma View.OnClickListener) ma non lo usa.*/
    }
    @Override
    public int getItemCount() {
        return notes != null ? notes.size() : 0;
    }

    //La classe NoteViewHolder è definita dentro NoteAdapter
    static class NoteViewHolder extends RecyclerView.ViewHolder {
        final TextView titleText;
        final Chip tagChip;
        //l’istanza del NoteViewHolder viene creata in onCreateViewHolder()…guarda sopra
        NoteViewHolder(ItemNoteBinding binding) {
            super(binding.getRoot());
            titleText = binding.textTitle;
            tagChip = binding.chipTag;
        }
    }
    /*RICORDA: una classe “nested static” non può catturare nulla che sia legato alla precipua istanza della classe che la contiene (NoteAdapter). Al limite può catturare i metodi e gli attributi “static”.
     Definire NoteViewHolder come static significa renderlo autonomo dall’istanza di NoteAdapter: niente catture implicite, meno rischi di leak, migliore separazione delle responsabilità. È la pratica consigliata per i RecyclerView.ViewHolder.*/
}