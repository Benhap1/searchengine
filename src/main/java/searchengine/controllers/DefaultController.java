//package searchengine.controllers;
//
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.RequestMapping;
//
//@Controller
//public class DefaultController {
//
//    /**
//     * Метод формирует страницу из HTML-файла index-en.html,
//     * который находится в папке resources/templates.
//     * Это делает библиотека Thymeleaf.
//     */
//    @RequestMapping("/")
//    public String index() {
//        return "index";
//    }
//}


package searchengine.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DefaultController {

    /**
     * Метод формирует страницу из HTML-файлов index-en.html или index-ru.html,
     * который находится в папке resources/templates.
     * Это делает библиотека Thymeleaf.
     * Параметр lang определяет, какой файл возвращается.
     */
    @RequestMapping("/")
    public String index(@RequestParam(name = "lang", defaultValue = "en") String lang) {
        if ("ru".equalsIgnoreCase(lang)) {
            return "index-ru";
        } else {
            return "index-en";
        }
    }
}
