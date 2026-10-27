package edu.skope.ai;

import edu.skope.controller.AuthController;
import edu.skope.repository.Db;
import edu.skope.service.DepartmentAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Supervisor-only view of the paid AI budget. Department staff and students cannot read it. */
@RestController
public class AiUsageController {
    private final AiBudget budget;
    private final AiService ai;
    private final DepartmentAccess access;
    private final AuthController auth;

    public AiUsageController(AiBudget budget, AiService ai, DepartmentAccess access, AuthController auth) {
        this.budget = budget; this.ai = ai; this.access = access; this.auth = auth;
    }

    @GetMapping("/api/admin/ai-usage")
    public Map<String, Object> usage(Authentication a) {
        access.requireSupervisor(Db.id(auth.user(a), "id"));
        var out = new LinkedHashMap<String, Object>(budget.status());
        out.put("aiMode", ai.mode());
        out.put("embeddingMode", ai.embeddingMode());
        return out;
    }
}
