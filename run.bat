java -cp %JADE_PATH%;out/production/AIAD jade.Boot -agents drHouse:agents.RecursoAgent(raio-x);drHouse1:agents.RecursoAgent(tac);p1:agents.PacienteAgent(3,false,raio-x,tac,raio-x,raio-x);p2:agents.PacienteAgent(5,false,tac,raio-x) -gui