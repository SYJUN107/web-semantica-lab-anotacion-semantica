package es.uc3m.miaa.utils;

import gate.*;
import gate.creole.*;
import gate.util.persistence.*;
import gate.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.net.URL;

public class MyGATE {

    private static MyGATE instance= null;
    private ConditionalSerialAnalyserController controller;
    private Document currentDoc= null;
    
    private MyGATE() {
	try {
	    // initialise the GATE library
	    Gate.init();

	    // load the ANNIE plugin
	    Plugin anniePlugin = new Plugin.Maven("uk.ac.gate.plugins",
						  "annie",
						  gate.Main.version);
	    Gate.getCreoleRegister().registerPlugin(anniePlugin);

	    // load ANNIE application from inside the plugin
	    controller = (ConditionalSerialAnalyserController)
		PersistenceManager.loadObjectFromUrl(
			new ResourceReference(anniePlugin, "resources/" +
					      ANNIEConstants.DEFAULT_FILE).toURL());
	} catch (Exception e) {
	    e.printStackTrace();
	}
    }

    public static synchronized MyGATE getInstance() {
	if (instance==null) {
	    instance= new MyGATE();
	}
	return instance;
    }

    public AnnotationSet annotate(String text) {

	try {
	    currentDoc= Factory.newDocument(text);
	    return annotate();
	} catch (Exception e) {
	    e.printStackTrace();
	    return null;
	}
    }

    public AnnotationSet annotate(URL sourceUrl) {

	try {
	    currentDoc= Factory.newDocument(sourceUrl);
	    return annotate();
	} catch (Exception e) {
	    e.printStackTrace();
	    return null;
	}
    }

    private AnnotationSet annotate() throws Exception {

	    Corpus _corpus= Factory.newCorpus("My Corpus");
	    _corpus.add(currentDoc);

	    controller.setCorpus(_corpus);
	    controller.execute();
	    return currentDoc.getAnnotations();
    }

    public List<Entity> findEntities(String text) {
	try {
	    AnnotationSet annieAnnot= annotate(text);
	    return findEntities(annieAnnot);
	} catch (Exception e) {
	    e.printStackTrace();
	    return null;
	}
    }
	    
    public List<Entity> findEntities(URL sourceUrl) {
	try {
	    AnnotationSet annieAnnot= annotate(sourceUrl);
	    return findEntities(annieAnnot);
	} catch (Exception e) {
	    e.printStackTrace();
	    return null;
	}
    } 
	    
    private List<Entity> findEntities(AnnotationSet annieAnnot)
	throws Exception{
	    CopyOnWriteArraySet<String> entityTypes=
		new CopyOnWriteArraySet<String>(Arrays.asList("Person",
							      "Location",
							      "Organization"));
	    AnnotationSet entities= annieAnnot.get(entityTypes);
	    Iterator<Annotation> entitiesIt= entities.iterator();
	    ArrayList<Entity> entList= new ArrayList<Entity>();
	    while(entitiesIt.hasNext()) {
		Annotation entityA= entitiesIt.next();
		Entity entityE= new Entity();
		entityE.setType(entityA.getType());
		entityE.setText(currentDoc.getContent().getContent(entityA.getStartNode().getOffset(),entityA.getEndNode().getOffset()).toString());
		entList.add(entityE);
	    }
	    return entList;
    }
}
	    
	
	
