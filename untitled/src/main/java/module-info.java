module pl.umcs.oop {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.sql;

    opens pl.umcs.oop to javafx.fxml;

    exports pl.umcs.oop;
}