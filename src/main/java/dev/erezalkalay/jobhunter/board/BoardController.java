package dev.erezalkalay.jobhunter.board;

import dev.erezalkalay.jobhunter.application.dto.ApplicationResponse;
import dev.erezalkalay.jobhunter.board.dto.BoardCardResponse;
import dev.erezalkalay.jobhunter.job.dto.JobRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/board")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping
    public List<BoardCardResponse> loadBoard() {
        return boardService.loadBoard();
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApplicationResponse> addAndTrackJob(@Valid @RequestBody JobRequest request) {
        BoardService.AddJobResult result = boardService.addAndTrackJob(request);
        // 201 when a new Board ticket was created, 200 when the job was already on the Board.
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApplicationResponse.from(result.application()));
    }
}
