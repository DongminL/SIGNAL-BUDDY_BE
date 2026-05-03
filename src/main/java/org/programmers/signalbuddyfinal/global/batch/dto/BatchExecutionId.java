package org.programmers.signalbuddyfinal.global.batch.dto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchExecutionId {

    private Long jobInstanceId;
    private Long jobExecutionId;
    private Long stepExecutionId;
}
