package controllers;

import models.Computer;
import play.db.jpa.JPA;
import play.db.jpa.Transactional;
import play.mvc.Controller;
import play.mvc.Result;

public class JApplication extends Controller {

//	@Transactional(readOnly = true)
//	public static Result find(Long id) {
//		return ok(JPA.em().find(Computer.class, id).toString());
//	}
//
//	@Transactional
//	public static Result create() {
//		Computer computer = new Computer();
//		computer.name = "new name " + System.currentTimeMillis();
//
//		computer.save();
//
//		return ok(computer.toString());
//	}
}