# BOQA

Simple usage by:


		// Test usage
		BoqaService service = new BoqaService(ontologyFile, annotationFile);
		service.scoreItemsForTestQuery();

		BOQACore.setAssociationFileType(Type.PAF);
		BOQACore boqaCore = new BOQACore(ontologyFile, annotationFile);

		List<Integer> queryAsBoqaIndices = new ArrayList<Integer>();
		queryAsBoqaIndices.add(1);
		queryAsBoqaIndices.add(2);
		queryAsBoqaIndices.add(3);

		List<ItemResultEntry> resultList = boqaCore.score(queryAsBoqaIndices);

		for (int i = 0; i < 10; i++) {

			int boqaId = resultList.get(i).getItemId();

			String itemName = boqaCore.getItemName(boqaId);

			double score = resultList.get(i).getScore();

			System.out.println("boqaId :" + boqaId);
			System.out.println("itemName :" + itemName);
			System.out.println("score :" + score);
			System.out.println();

		}
	}
