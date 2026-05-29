package com.sashkomusic.api.controller;

import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.dto.TrackFilterRequest;
import com.sashkomusic.api.dto.TrackWithTagsDto;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tracks")
@AllArgsConstructor
public class TrackController {

    private final TrackService trackService;

    @GetMapping("")
    public ResponseEntity<TrackDto> findTrack(@RequestParam String title) {
        return ResponseEntity.ok(trackService.findByTitle(title));
    }

    @GetMapping("/search")
    public ResponseEntity<TrackDto> findTrackByArtistAndTitle(
            @RequestParam String artist,
            @RequestParam String title) {
        return ResponseEntity.ok(trackService.findByArtistAndTitle(artist, title));
    }

    @PostMapping("/filter")
    public ResponseEntity<List<TrackWithTagsDto>> findAllTracks(
            @RequestBody TrackFilterRequest request) {
        return ResponseEntity.ok(trackService.findAllByTags(request.tags()));
    }
}
