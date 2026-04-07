package org.programmers.signalbuddyfinal.domain.like.batch;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.programmers.signalbuddyfinal.domain.feedback.entity.Feedback;
import org.programmers.signalbuddyfinal.domain.feedback.repository.FeedbackRepository;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeRequestType;
import org.programmers.signalbuddyfinal.domain.like.dto.LikeUpdateRequest;
import org.programmers.signalbuddyfinal.domain.like.repository.LikeJdbcRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class RequestLikeWriter implements ItemWriter<LikeUpdateRequest> {

    private final FeedbackRepository feedbackRepository;
    private final LikeJdbcRepository likeJdbcRepository;

    @Transactional
    @Override
    public void write(Chunk<? extends LikeUpdateRequest> chunk) {
        log.info("like job chunk size : {}", chunk.size());

        List<LikeUpdateRequest> savedLikeList = new ArrayList<>();
        List<LikeUpdateRequest> deletedLikeList = new ArrayList<>();

        for (LikeUpdateRequest request : chunk.getItems()) {
            Feedback feedback = feedbackRepository.findById(request.getFeedbackId()).orElse(null);
            if (feedback == null) {
                continue;
            }

            if (LikeRequestType.ADD.equals(request.getLikeRequestType())) {
                savedLikeList.add(request);
                feedback.increaseLike();

            } else if (LikeRequestType.CANCEL.equals(request.getLikeRequestType())) {
                deletedLikeList.add(request);
                feedback.decreaseLike();
            }
        }

        likeJdbcRepository.saveAllInBatch(savedLikeList);
        likeJdbcRepository.deleteAllByLikeRequestsInBatch(deletedLikeList);
    }
}
