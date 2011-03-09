package org.neo4j.server.ext.visualization;

import com.vaadin.Application;
import com.vaadin.ui.Window;
import org.neo4j.graphdb.GraphDatabaseService;

/**
 * The Application's "main" class
 */
@SuppressWarnings("serial")
public class WidgetTestApplication extends Application {
    private Window window;
    private GraphDatabaseService graphDb;

    public WidgetTestApplication() {}

    @Override
    public void init() {
        window = new Window("Widget Test");
        setMainWindow(window);
        window.addComponent(new GraphComponent(graphDb));
    }

}
