package dev.erezalkalay.jobhunter.company;

import java.util.List;

/** A company on the user's board with the distinct titles of all their jobs there. */
public record CompanyPositions(String company, List<String> positions) {
}
