package dev.worldcup.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Only public application entry routes share the bundled frontend document. */
@Controller
public class WebEntryController {
    @GetMapping({"/", "/shares/{token}"})
    public String entry() {
        return "forward:/index.html";
    }
}
