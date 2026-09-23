package net.vaier.application;

import net.vaier.domain.OpenService;

import java.util.List;

public interface JudgeOpenServicesUseCase {

    /** What the last looks mean: the open services admins have not yet been told about. */
    List<OpenService> judgeOpenServices();
}
