package com.mycompanyname.zero.localization.web;

import com.mycompanyname.zero.localization.LocalizationService;
import com.mycompanyname.zero.localization.web.dto.LanguageDto;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public localization endpoints used by the frontend to bootstrap its i18n before authentication.
 */
@RestController
@RequestMapping("/api/localization")
@RequiredArgsConstructor
public class LocalizationController {

    private final LocalizationService localizationService;

    @GetMapping("/languages")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
    public List<LanguageDto> languages() {
        return localizationService.getLanguages();
    }

    @GetMapping("/{culture}")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
    public Map<String, String> dictionary(@PathVariable String culture) {
        return localizationService.getDictionary(culture);
    }
}
