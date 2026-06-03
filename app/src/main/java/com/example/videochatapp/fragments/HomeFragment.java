package com.example.videochatapp.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.videochatapp.R;
import com.example.videochatapp.activities.CallActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import java.util.UUID;

public class HomeFragment extends Fragment {

    private TextInputEditText etRoomId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        etRoomId = view.findViewById(R.id.et_room_id);
        MaterialButton btnStartCall = view.findViewById(R.id.btn_start_call);
        MaterialButton btnJoinCall = view.findViewById(R.id.btn_join_call);

        btnStartCall.setOnClickListener(v -> startCall());
        btnJoinCall.setOnClickListener(v -> joinCall());

        return view;
    }

    private void startCall() {
        // Generate a random 6-character room ID
        String roomId = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        //String roomId = "1";
        
        Intent intent = new Intent(getActivity(), CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_ROOM_ID, roomId);
        intent.putExtra(CallActivity.EXTRA_IS_CREATOR, true);
        startActivity(intent);
    }

    private void joinCall() {
        if (etRoomId.getText() == null) return;
        String roomId = etRoomId.getText().toString().trim();
        
        if (roomId.isEmpty()) {
            Toast.makeText(getActivity(), "Please enter a Room ID", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(getActivity(), CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_ROOM_ID, roomId);
        intent.putExtra(CallActivity.EXTRA_IS_CREATOR, false);
        startActivity(intent);
    }
}
