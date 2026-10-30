package cn.swjtu.dispatch;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({"/rider", "/rider/"})
    public String rider() { return "forward:/rider.html"; }
}
