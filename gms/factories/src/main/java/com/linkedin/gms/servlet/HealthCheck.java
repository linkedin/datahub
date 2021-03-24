package com.linkedin.gms.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// Return a 200 for kubernetes healthchecks.
public class HealthCheck extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
    resp.setStatus(200);
  }
}