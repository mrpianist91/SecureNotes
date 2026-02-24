package com.example.securenotes.feature_vault;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.example.securenotes.core.VaultFile;
import com.example.securenotes.feature_vault.databinding.ItemVaultFileBinding;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VaultAdapter extends ListAdapter<VaultFile, VaultAdapter.ViewHolder> {

    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(VaultFile file);
    }

    public VaultAdapter(OnItemClickListener listener) {
        super(new DiffCallback());
        this.listener = listener;
    }

    // Helper utile per lo swipe-to-delete
    public VaultFile getFileAt(int position) {
        return getItem(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Il ViewBinding genera automaticamente la classe ItemVaultFileBinding dall'XML
        ItemVaultFileBinding binding = ItemVaultFileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));//.bind() è un metodo definito da me sotto
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemVaultFileBinding binding;
        // Formatter per la data
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

        ViewHolder(ItemVaultFileBinding binding) {
            super(binding.getRoot());//binding.getRoot()=itemView è la ConstraintLayout dell'item_vault_file
            this.binding = binding;

            // Gestione Click nel costruttore
            //NB itemView è la stessa cosa di binding.getRoot(). E' la ConstraintLayout dell'item_vault_file
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onItemClick(getItem(pos));
                }
            });
        }

        void bind(VaultFile file) {
            // Recupera il Context dalla view
            Context context = itemView.getContext();
            // Nome File
            binding.tvFileName.setText(file.name != null ? file.name : "Sconosciuto");

            // MAPPING CORRETTO: file.addedAt + MimeType
            // Formattazione Data
            String dateText = dateFormat.format(new Date(file.addedAt));

            // Pulizia MimeType (es. da "application/pdf" a "PDF") per UX migliore
            String typeText = "FILE";
            if (file.mimeType != null && file.mimeType.contains("/")) {
                typeText = file.mimeType.substring(file.mimeType.lastIndexOf("/") + 1).toUpperCase();
            } else if (file.mimeType != null) {
                typeText = file.mimeType.toUpperCase();
            }

            // Setta "Data - TIPO" (Rimossa la dimensione che non esiste nell'Entity)
            binding.tvFileDetails.setText(context.getString(R.string.vault_file_details_format, dateText, typeText));
        }
    }

    static class DiffCallback extends DiffUtil.ItemCallback<VaultFile> {
        @Override
        public boolean areItemsTheSame(@NonNull VaultFile oldItem, @NonNull VaultFile newItem) {
            // id è String, quindi si usa .equals(), non ==
            return oldItem.id.equals(newItem.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull VaultFile oldItem, @NonNull VaultFile newItem) {
            // Confronta i campi visualizzati per evitare refresh inutili
            // Nota: path potrebbe non essere visualizzato ma è importante per la logica
            return oldItem.path.equals(newItem.path) &&
                    (oldItem.name != null && oldItem.name.equals(newItem.name));
        }
    }
}